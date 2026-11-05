(ns metabase.serialization.test-util
  "Stub serialization resolvers, for tests that should not touch the app DB."
  (:require
   [metabase.serialization.resolve :as resolve]))

(defn- unstubbed [k]
  (fn [& _]
    (throw (ex-info (str "No stub for " k) {:stub k}))))

(defn- each [f xs]
  (into {} (keep (fn [x] (when-some [v (f x)] [x v]))) (distinct xs)))

(defn stub-export-resolver
  "An export resolver answering with the fns of `stubs`, a nil answer meaning no target: `:fk` of an id and a model,
  `:fk-keyed` of an id, a model and a field, `:table` of a Table id and `:field` of a Field id."
  [stubs]
  (let [stub #(get stubs % (unstubbed %))]
    (reify resolve/ExportResolver
      (entity-ids   [_ model ids]       (each #((stub :fk) % model) ids))
      (field-values [_ model field ids] (each #((stub :fk-keyed) % model field) ids))
      (table-refs   [_ table-ids]       (each (stub :table) table-ids))
      (field-refs   [_ field-ids]       (each (stub :field) field-ids)))))

(defn stub-import-resolver
  "An import resolver answering with the fns of `stubs`, a nil answer meaning no target: `:fk` of a portable id and a
  model, `:fk-keyed` of a value, a model and a field, `:user` of an email, `:database` of a Database name, `:table` of
  a Table path and `:field` of a Field path."
  [stubs]
  (let [stub #(get stubs % (unstubbed %))]
    (reify resolve/ImportResolver
      (entity-pks   [_ model eids]         (each #((stub :fk) % model) eids))
      (pks-by-field [_ model field values] (each #((stub :fk-keyed) % model field) values))
      (user-ids     [_ emails]             (each (stub :user) emails))
      (database-ids [_ db-names]           (each (stub :database) db-names))
      (table-ids    [_ paths]              (each (stub :table) paths))
      (field-ids    [_ paths]              (each (stub :field) paths)))))
