;;
;; Copyright (c) Two Sigma Open Source, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;  http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
(ns cook.test.util
  (:require [clojure.test :refer :all]
            [cook.util :refer :all]))

(deftest test-diff-map-keys
  (is (= [#{:b} #{:c} #{:a :d}]
         (diff-map-keys {:a {:a :a}
                         :b {:b :b}
                         :d {:d :d}}
                        {:a {:a :a}
                         :c {:c :c}
                         :d {:d :e}})))
  (is (= [nil #{:c} #{:a :d}]
         (diff-map-keys {:a {:a :a}
                         :d {:d :d}}
                        {:a {:a :a}
                         :c {:c :c}
                         :d {:d :e}})))
  (is (= [#{:b} nil #{:a :d}]
         (diff-map-keys {:a {:a :a}
                         :b {:b :b}
                         :d {:d :d}}
                        {:a {:a :a}
                         :d {:d :e}}))))
