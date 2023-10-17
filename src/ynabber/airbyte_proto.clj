(ns ynabber.airbyte-proto
  (:refer-clojure :exclude [read])
  (:require [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            #_[json-schema.core :as json-schema]
            [scjsv.core :as json-schema]))

(def msg-key-to-type
  {:spec :SPEC
   :connectionStatus :CONNECTION_STATUS
   :catalog :CATALOG
   :record :RECORD
   :state :STATE})

(def msg-schema
  (-> (io/resource "AirbyteMessage.schema.json")
      (slurp)
      (json/decode true)))

(def validate
  (json-schema/validator msg-schema))

(defn ser-message [[msg-key msg-body]]
  (let [msg {msg-key msg-body
             :type (msg-key-to-type msg-key)}
        errors (validate msg)]
    (if errors
      (throw (ex-info "Invalid message emitted"
                      {:errors errors}))
      (json/encode msg))))

(defprotocol SourceConnector
  (spec [conn]
    "A value conforming to the ConnectorSpecification schema")
  (check [conn config]
    "A value conforming to the AirbyteConnectionStatus schema")
  (discover [conn config]
    "A value conforming to the AirbyteCatalog schema")
  (read [conn config catalog state]
    "A sequence of values conforming to AirbyteRecordMessage or AirbyteStateMessage"))

(defmulti dispatch :command)

(defmethod dispatch :spec [_ connector]
  [(ser-message (spec connector))])

(defmethod dispatch :check [{:keys [config]} connector]
  (let [config (json/decode (slurp config)
                            true)]
    [(ser-message (check connector config))]))

(defmethod dispatch :discover [{:keys [config]} connector]
  (let [config (json/decode (slurp config)
                            true)]
    [(ser-message (discover connector config))]))

; TODO optionally validate each record according to the catalog schema
(defmethod dispatch :read [{:keys [config catalog state]} connector]
  (let [config (json/decode (slurp config)
                            true)
        catalog (json/decode (slurp catalog)
                             true)
        state (when state
                (json/decode (slurp state)
                             true))]
    (sequence (map ser-message)
              (read connector config catalog state))))

(defn workspace-path [path]
  (if-let [job-id (System/getenv "WORKER_JOB_ID")]
    (str (io/file "/data" job-id (System/getenv "WORKER_JOB_ATTEMPT") path))
    path))

(defn make-main [connector]
  (fn -main [& args]
    (let [args (cli/parse-opts args
                               {:args->opts [:command]
                                :coerce {:command keyword
                                         :config workspace-path
                                         :catalog workspace-path
                                         :state workspace-path}})
          messages (dispatch args connector)]
      (doseq [msg messages]
        (println msg)))))
