(ns ynabber.open-api
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [com.rpl.specter :as spr]))

(def api-spec-resource "ynab_v1.openapi_v3.yaml")

(def api-spec
  (-> (io/resource api-spec-resource)
      (slurp)
      (yaml/parse-string)))

; Navigate to a component schema
; .. to its refs
; .. to their refs

(def REFS
  (spr/recursive-path [] recurse
    (spr/cond-path
      :$ref (spr/stay-then-continue)
      ;map? [spr/ALL
      ;      recurse]
      vector? [spr/ALL
               recurse])))

(spr/select REFS
            [{:$ref :hi}])

(spr/select [:components :schemas :CategoryGroupWithCategories REFS]
            api-spec)
