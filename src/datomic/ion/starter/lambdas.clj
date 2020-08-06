;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns datomic.ion.starter.lambdas
  (:require
   [clojure.data.json :as json]
   [datomic.client.api :as d]
   [datomic.ion.starter :as starter]
   [datomic.ion.starter.edn :as edn]))

(defn get-schema
  "Lambda ion that returns database schema."
  [_]
  (-> (starter/get-db)
      starter/get-schema
      edn/write-str))

(defn get-items-by-type
  "Lambda ion that returns items matching type."
  [{:keys [input]}]
  (-> (starter/get-db)
      (starter/get-items-by-type (-> input json/read-str keyword)
                             [:inv/sku :inv/size :inv/color])
      edn/write-str))

(defn ensure-sample-dataset
  "Lambda ion that creates database and transacts sample data."
  [_]
  (-> (starter/ensure-sample-dataset)
      edn/write-str))

(defn pagination
  "Lambda ion that enables paging."
  [{:keys [input]}]
  (let [inst->iso-string (fn [moment]
                           (let [s (pr-str moment)]
                             (subs s 7 (- (.length s) 1))))
        ;TODO: Replace this garbage inst->iso-string
        value-fn (fn [k v]
                   (condp instance? v
                     java.util.Date (inst->iso-string v)
                     java.util.UUID (str v)
                     v))]
    (-> (starter/get-db "jgehtland-load-test")
        (starter/paginated-contracts-ion
          "admin@user.com"
          #uuid"eb609fca-6fd6-4c47-a36a-fe6762498b36"
          (or
            ;(some-> input json/read-str)
            {}))
        (json/write-str :value-fn value-fn))))
