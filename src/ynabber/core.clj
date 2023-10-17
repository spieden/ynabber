(ns ynabber.core
  (:refer-clojure :exclude [read])
  (:require [com.rpl.specter :as spr]
            [hashp.core]
            [malli.json-schema :as malli-json]
            [martian.core :as martian]
            [martian.hato :as martian-http]
            [ynabber.airbyte-proto :as airbyte-proto]
            [ynabber.schemas :as schemas])
  (:import (clojure.lang ExceptionInfo)
           (java.time Instant)))

(def api-spec-resource "ynab_v1.openapi_v3.yaml")

(defn add-authentication-header [token]
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (assoc-in ctx
                      [:request :headers "Authorization"]
                      (str "Bearer " token)))})

(defn make-client [token]
  (martian-http/bootstrap-openapi api-spec-resource
                                  {:interceptors (concat martian-http/hato-interceptors
                                                         [(add-authentication-header token)
                                                          martian-http/perform-request])}))
(def conn-spec-schema
  [:map
   [:token :string]
   [:budget-id :string]])

(def conn-spec-message
  [:spec {:connectionSpecification (malli-json/transform conn-spec-schema)}])

(defn check [{:keys [token budget-id]}]
  (let [client (make-client token)
        result (try (martian/response-for client
                                          :get-budget-settings-by-id
                                          {:budget-id budget-id})
                    (catch ExceptionInfo e e))]
    [:connectionStatus
     (if (instance? ExceptionInfo result)
       {:message (:body (ex-data result))
        :status :FAILED}
       {:message (str "Successfully read currency symbol: "
                      (spr/select-one! [:body :data :settings :currency_format :currency_symbol]
                                       result))
        :status :SUCCEEDED})]))

(defn discover []
  [:catalog {:streams [{:name :category-groups
                        :json_schema (schemas/extract-stream-schema :CategoryGroupWithCategories)
                        :supported_sync_modes #{:full_refresh
                                                :incremental}}
                       {:name :transactions
                        :json_schema (schemas/extract-stream-schema :TransactionDetail)
                        :supported_sync_modes #{:full_refresh
                                                :incremental}}]}])

(def stream->request-and-items-key
  {:category-groups [:get-categories :category_groups]
   :transactions [:get-transactions :transactions]})

(defn read-stream [client stream budget-id last-server-knowledge]
  (let [[request items-key] (stream->request-and-items-key stream)
        params {:budget-id budget-id
                :server-knowledge last-server-knowledge}
        response (martian/response-for client request params)
        records (spr/select-one! [:body :data items-key some?] response)
        new-server-knowledge (spr/select-one! [:body :data :server_knowledge some?]
                                              response)]
    (conj (mapv (fn [record]
                  [:record {:data record
                            :stream stream
                            :emitted_at (.toEpochMilli (Instant/now))}])
                records)
          [:state {:stream {:stream_descriptor {:name stream}
                            :stream_state {:server_knowledge new-server-knowledge}}}])))

(defn read [config catalog state]
  (let [client (make-client (:token config))]
    (sequence (mapcat (fn [configured-stream]
                        (read-stream client
                                     (-> configured-stream
                                         :stream
                                         :name
                                         keyword)
                                     (:budget-id config)
                                     nil)))
              (:streams catalog))))

(defrecord YNABSourceConnector []
  airbyte-proto/SourceConnector
  (spec [_] conn-spec-message)
  (check [_ config] (check config))
  (discover [_ config] (discover))
  (read [conn config catalog state] (read config catalog state)))

(def -main
  (airbyte-proto/make-main (YNABSourceConnector.)))

(comment
  (def e (try (wrap-message (conn-spec-message))
              (catch Exception e e))))
