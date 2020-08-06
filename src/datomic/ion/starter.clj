;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns datomic.ion.starter
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datomic.client.api :as d]
    [datomic.ion.starter.utils :as utils]
    [datomic.ion.starter.rules :as starter-rules]))

(def database-name "datomic-docs-tutorial")

(def get-client
  "Return a shared client. Set datomic/ion/starter/config.edn resource
  before calling this function."
  (memoize #(if-let [r (io/resource "datomic/ion/starter/config.edn")]
              (d/client (edn/read-string (slurp r)))
              (throw (RuntimeException. "You need to add a resource datomic/ion/starter/config.edn with your connection config")))))

(defn get-connection
  "Get shared connection."
  ([] (get-connection database-name))
  ([database-name]
   (utils/with-retry #(d/connect (get-client) {:db-name database-name}))))

(defn- ensure-dataset
  "Ensure that a database named db-name exists, running setup-fn
  against the shared connection. Returns result of setup-fn"
  [db-name setup-sym]
  (require (symbol (namespace setup-sym)))
  (let [setup-var (resolve setup-sym)
        client (get-client)]
    (when-not setup-var
      (utils/anomaly! :not-found (str "Could not resolve " setup-sym)))
    (d/create-database client {:db-name db-name})
    (let [conn (get-connection)]
      (setup-var conn))))

(defn ensure-sample-dataset
  "Creates db (if necessary) and transacts sample data."
  []
  (utils/with-retry #(ensure-dataset database-name 'datomic.ion.starter.inventory/load-dataset)))

(defn get-db
  "Returns current db value from shared connection."
  ([]
   (get-db database-name))
  ([database-name]
   (d/db (get-connection database-name))))

(defn get-schema
  "Returns a data representation of db schema."
  [db]
  (->> (d/pull db '{:eid 0 :selector [{:db.install/attribute [*]}]})
       :db.install/attribute
       (remove (fn [m] (str/starts-with? (namespace (:db/ident m)) "db")))
       (map #(update % :db/valueType :db/ident))
       (map #(update % :db/cardinality :db/ident))))

(defn get-items-by-type
  "Returns pull maps describing all items matching type"
  [db type pull-expr]
  (d/q '[:find (pull ?e pull-expr)
         :in $ ?type pull-expr
         :where [?e :inv/type ?type]]
       db type pull-expr))

(def group-pull [:group/id :group/name
                 {:group/type [:db/ident]}])

(def contract-reporting-pull [:contract/id
                              :contract/name
                              :contract/description
                              :contract/start-date
                              :contract/expiration-date
                              :contract/effective-date
                              :contract/original-term
                              :contract/contains-baa
                              :contract/fair-market-valuation-attached
                              :contract/renewal-times
                              :contract/renewal-term
                              :contract/renewal-notice-days
                              :contract/review-months
                              :contract/archived?
                              {:contract/renewal-type                [:db/ident]
                               :contract/primary-responsible-party   [:user/id :user/name :user/given-name :user/family-name]
                               :contract/secondary-responsible-party [:user/id :user/name :user/given-name :user/family-name]
                               :contract/tertiary-responsible-party  [:user/id :user/name :user/given-name :user/family-name]
                               :contract/status                      [:db/ident]
                               :contract/type                        [:contract-type/id
                                                                      :contract-type/name
                                                                      :contract-type/categories [:db/ident]
                                                                      :contract-type/uses-timesheets?]
                               :contract/group                       group-pull
                               :contract/customer-organization       group-pull
                               :contract/operational-groups          group-pull
                               :contract/entity-group                group-pull
                               :contract/main-docs                   [:attachment/category :attachment/effective-date :attachment/expiration-date]
                               :contract/vendor                      [:organization/name]
                               :contract/secondary-vendors           [:organization/name]
                               :contract/workflows                   [:workflow/id
                                                                      {:workflow/status           [:db/ident]
                                                                       :workflow/request-workflow [:workflow/name]
                                                                       :workflow/requestor        [:user/given-name :user/family-name]}]
                               :contract/assets                      [:asset/item]
                               :contract/attachments                 [:attachment/category :attachment/effective-date :attachment/expiration-date]
                               :contract/linked-contracts            [:contract/name]
                               :contract/signatories                 [:user/given-name :user/family-name
                                                                      :person/given-name :person/family-name]}])

(def contract-library-pull
  [:contract/id
   :contract/name
   :contract/description
   :contract/effective-date
   :contract/expiration-date
   :contract/start-date
   :contract/archived?
   {:contract/vendor                      [:organization/id :organization/name]
    :contract/status                      [:db/ident]
    :contract/type                        [:contract-type/id
                                           :contract-type/name
                                           :contract-type/categories [:db/ident]]
    :contract/primary-responsible-party   [:user/family-name :user/given-name]
    :contract/secondary-responsible-party [:user/family-name :user/given-name]
    :contract/tertiary-responsible-party  [:user/family-name :user/given-name]
    :contract/operational-groups          [{:group/type [:db/ident]}
                                           :group/name]
    :contract/workflows                   [{:workflow/request-workflow [:workflow/name]
                                            :workflow/requestor        [:user/family-name :user/given-name]}]}])

(defn contracts-for-user-in-org
  [db username org-id]
  (map first
       (d/qseq '[:find (pull ?contract pattern)

                 :in $ % pattern ?username ?org-id
                 :where
                 [?user :user/name ?username]
                 [?org :group/id ?org-id]
                 (contracts-given-user-in-org ?user ?org ?contract)]
               db
               starter-rules/contract-rules
               [:db/id :contract/name]
               username
               org-id)))

(defn pull-many
  [db selector eids]
  (map first
       (d/qseq '[:find (pull ?e pattern)
                 :in $ pattern [?e ...]]
               db
               selector
               eids)))

(defn paginated-contracts-ion
  [db username group-id {:keys [limit offset order-by]
                         :or   {limit    1000
                                offset   0
                                order-by {:attr     :contract/name
                                          :reverse? false}}}]
  (let [{:keys [attr reverse?]} order-by
        compare-fn (if-not reverse?
                     compare
                     (fn [a b] (compare b a)))
        all-contracts (contracts-for-user-in-org db username group-id)]
    (->> all-contracts
         (sort-by attr compare-fn)
         (drop offset)
         (take limit)
         (map :db/id)
         (pull-many db contract-library-pull))))