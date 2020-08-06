;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns datomic.ion.starter.http
  (:require
    [clojure.java.io :as io]
    [datomic.ion.starter :as starter]
    [datomic.ion.starter.edn :as edn]
    [datomic.ion.lambda.api-gateway :as apigw]
    [clojure.data.json :as json]))

(defn edn-response
  [body]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body body})

(defn json-response
  [body]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body body})

(defn get-items-by-type
  "Web handler that returns info about items matching type."
  [{:keys [headers body]}]
  (let [type (some-> body edn/read)]
    (if (keyword? type)
      (-> (starter/get-db)
          (starter/get-items-by-type type [:inv/sku :inv/size :inv/color])
          edn/write-str
          edn-response)
      {:status 400
       :headers {}
       :body "Expected a request body keyword naming a type"})))

(def get-items-by-type-lambda-proxy
  (apigw/ionize get-items-by-type))


(defn pagination
  "Web handler that enables paging."
  [{:keys [headers body]}]
  (-> (starter/get-db)
      (starter/paginated-contracts-ion "admin@user.com"
                                       #uuid"eb609fca-6fd6-4c47-a36a-fe6762498b36"
                                       {})
      json/write-str
      json-response))

(def pagination-lambda-proxy
  (apigw/ionize pagination))
