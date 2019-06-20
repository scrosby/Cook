(ns cook.test.mesos.mesos-compute-cluster
  (:require [clojure.test :refer :all]
            [cook.scheduler.scheduler :as sched]
            [cook.mesos.mesos-compute-cluster :as mcc]
            [mesomatic.types :as mtypes]
            [clojure.data.json :as json]
            [cook.mesos.sandbox :as sandbox]
            [cook.mesos.heartbeat :as heartbeat])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(deftest test-in-order-status-update-processing
  (let [status-store (atom {})
        latch (CountDownLatch. 11)]
    (with-redefs [sched/handle-status-update
                  (fn [_ _ _ _ status]
                    (let [task-id (-> status :task-id :value str)]
                      (swap! status-store update task-id
                             (fn [statuses] (conj (or statuses [])
                                                  (-> status mtypes/pb->data :state)))))
                    (Thread/sleep (rand-int 100))
                    (.countDown latch))]
      (let [s (mcc/create-mesos-scheduler nil true nil nil nil nil nil nil nil nil nil)]
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {} :state :task-starting}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T1"} :state :task-starting}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T2"} :state :task-starting}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T1"} :state :task-running}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T2"} :state :task-running}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T3"} :state :task-starting}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T3"} :state :task-running}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T1"} :state :task-finished}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T3"} :state :task-failed}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {:value "T4"} :state :task-starting}))
        (.statusUpdate s nil (mtypes/->pb :TaskStatus {:task-id {} :state :task-failed}))

        (.await latch 4 TimeUnit/SECONDS)

        (is (= [:task-starting :task-failed] (->> "" (get @status-store) vec)))
        (is (= [:task-starting :task-running :task-finished] (->> "T1" (get @status-store) vec)))
        (is (= [:task-starting :task-running] (->> "T2" (get @status-store) vec)))
        (is (= [:task-starting :task-running :task-failed] (->> "T3" (get @status-store) vec)))
        (is (= [:task-starting] (->> "T4" (get @status-store) vec)))))))

(deftest test-framework-message-processing-delegation
  (let [framework-message-store (atom [])
        heartbeat-store (atom [])
        sandbox-store (atom [])]
    (with-redefs [heartbeat/notify-heartbeat (fn [_ _ _ framework-message]
                                               (swap! heartbeat-store conj framework-message))
                  sandbox/update-sandbox (fn [_ framework-message]
                                           (swap! sandbox-store conj framework-message))
                  sched/handle-framework-message (fn [_ _ framework-message]
                                                   (swap! framework-message-store conj framework-message))]
      (let [s (mcc/create-mesos-scheduler nil true nil nil nil nil nil nil nil nil nil)
            make-message (fn [message] (-> message json/write-str str (.getBytes "UTF-8")))]

        (testing "message delegation"
          (let [task-id "T1"
                executor-id (-> task-id mtypes/->ExecutorID mtypes/data->pb)
                m1 {"task-id" task-id}
                m2 {"task-id" task-id, "timestamp" 123456, "type" "heartbeat"}
                m3 {"exit-code" 0, "task-id" task-id}
                m4 {"task-id" task-id, "timestamp" 123456, "type" "heartbeat"}
                m5 {"sandbox" "/path/to/a/directory", "task-id" task-id, "type" "directory"}]

            (.frameworkMessage s nil executor-id nil (make-message m1))
            (.frameworkMessage s nil executor-id nil (make-message m2))
            (.frameworkMessage s nil executor-id nil (make-message m3))
            (.frameworkMessage s nil executor-id nil (make-message m4))
            (.frameworkMessage s nil executor-id nil (make-message m5))

            (let [latch (CountDownLatch. 1)]
              (sched/async-in-order-processing task-id #(.countDown latch))
              (.await latch))

            (is (= [m1 m3] @framework-message-store))
            (is (= [m2 m4] @heartbeat-store))
            (is (= [m5] @sandbox-store))))))))

(deftest test-in-order-framework-message-processing
  (let [messages-store (atom {})
        latch (CountDownLatch. 11)]
    (with-redefs [heartbeat/notify-heartbeat (constantly true)
                  sched/handle-framework-message
                  (fn [_ _ framework-message]
                    (let [{:strs [message task-id]} framework-message]
                      (swap! messages-store update (str task-id) (fn [messages] (conj (or messages []) message))))
                    (Thread/sleep (rand-int 100))
                    (.countDown latch))]
      (let [s (mcc/create-mesos-scheduler nil true nil nil nil nil nil nil nil nil nil)
            foo 11
            bar 21
            fee 31
            fie 41
            make-message (fn [index message]
                           (-> {"message" message, "task-id" (str "T" index)}
                               json/write-str
                               str
                               (.getBytes "UTF-8")))]

        (.frameworkMessage s nil (-> "" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 0 foo))
        (.frameworkMessage s nil (-> "T1" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 1 foo))
        (.frameworkMessage s nil (-> "T2" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 2 foo))
        (.frameworkMessage s nil (-> "T1" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 1 bar))
        (.frameworkMessage s nil (-> "T2" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 2 bar))
        (.frameworkMessage s nil (-> "T3" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 3 foo))
        (.frameworkMessage s nil (-> "T3" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 3 bar))
        (.frameworkMessage s nil (-> "T1" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 1 fee))
        (.frameworkMessage s nil (-> "T3" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 3 fie))
        (.frameworkMessage s nil (-> "T4" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 4 foo))
        (.frameworkMessage s nil (-> "" mtypes/->ExecutorID mtypes/data->pb) nil (make-message 0 fie))

        (.await latch 4 TimeUnit/SECONDS)

        (is (= [foo fie] (->> "T0" (get @messages-store) vec)))
        (is (= [foo bar fee] (->> "T1" (get @messages-store) vec)))
        (is (= [foo bar] (->> "T2" (get @messages-store) vec)))
        (is (= [foo bar fie] (->> "T3" (get @messages-store) vec)))
        (is (= [foo] (->> "T4" (get @messages-store) vec)))))))