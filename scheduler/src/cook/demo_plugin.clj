(ns cook.demo-plugin
  (:require [clj-http.client :as http]
            [clj-time.core :as t]
            [cook.cache :as ccache]
            [cook.hooks-definitions :as chd]))


(def http-timeout-millis 2000)
(def reqdict {:socket-timeout http-timeout-millis :conn-timeout http-timeout-millis
               :as :json-string-keys :content-type :json})

(defn- generate-result
       [result message]
       {:status result :message message :cache-expires-at (-> 1 t/seconds t/from-now)})

(defrecord DemoValidate [submit-url launch-url]
  chd/SchedulerHooks
  (chd/check-job-submission
    [this job-map]
    (let [{:keys [body] http-status :status :as response} (http/get submit-url reqdict)]

      (case http-status
        200 (let [status (get body "status")
                  message (or (get body "message") "No message sent.")]
              (case status
                "accepted" (generate-result :accepted message)
                "rejected" (generate-result :rejected message)
                (generate-result :rejected (str "Bad contents[1], illegal status message " body))))

        404 (generate-result :rejected  (str "Got 404 accessing " submit-url))
        :rejected  (str "Got nothing " response))))
  (chd/check-job-invocation
    [this job-map]
    (let [{:keys [body] http-status :status :as response} (http/get launch-url reqdict)]

      (case http-status
        200 (let [status (get body "status")
                  message (or (get body "message") "No message sent.")]
              (case status
                "accepted" (generate-result :accepted message)
                "deferred" (generate-result :rejected message)
                (generate-result :rejected (str "Bad contents[2], illegal status message " body))))

        404 (generate-result :rejected  (str "Got 404 accessing " launch-url))
        (generate-result :rejected (str "Got nothing " response))))))

(defn factory [{:keys [submit-url launch-url]}] (->DemoValidate submit-url launch-url))
