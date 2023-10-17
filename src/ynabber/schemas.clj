(ns ynabber.schemas
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(def api-spec-resource "ynab_v1.openapi_v3.yaml")

(def api-schemas
  (-> (io/resource api-spec-resource)
      (slurp)
      (yaml/parse-string)
      :components
      :schemas))

(defn ref->schema-name [path]
  (keyword (str/replace path
                        #"^#/components/schemas/"
                        "")))

(defn ref->schema [schemas path]
  (schemas (ref->schema-name path)))

(defn deref-schema [schema ref->schema]
  (walk/postwalk (fn [node]
                   (if (:$ref node)
                     (deref-schema (ref->schema (:$ref node))
                                   ref->schema)
                     node))
                 schema))

(defn merge-all-of [schema]
  (walk/postwalk (fn [node]
                   (if (and (:allOf node)
                            (not (some #(not= "object" (:type %))
                                       (:allOf node))))
                     (reduce (fn [agg sub]
                               (-> agg
                                   (update :properties #(merge % (:properties sub)))
                                   (update :required #(into % (:required sub)))))
                             (:allOf node))
                     node))
                 schema))

(defn extract-stream-schema [schema-name]
  (-> (api-schemas schema-name)
      (deref-schema (partial ref->schema api-schemas))
      (merge-all-of)))
