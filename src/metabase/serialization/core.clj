(ns metabase.serialization.core
  "Serialization (SerDes v3): exporting entities into portable maps and importing them back, in bulk.

  Exported entities refer to each other by portable references rather than database ids; a resolver (see
  [[metabase.serialization.resolve]]) translates the references of a whole batch at once."
  (:refer-clojure :exclude [import load])
  (:require
   [metabase.serialization.db :as serialization.db]
   [metabase.serialization.fk :as serialization.fk]
   [metabase.serialization.resolve :as resolve]
   [metabase.serialization.resolve.app-db :as resolve.app-db]
   [metabase.util.namespaces :as shared.ns]))

(shared.ns/import-fns
 [serialization.fk
  database-transform
  export-databases
  export-fields
  export-fks
  export-tables
  export-users
  field-transform
  fk-transform
  import-databases
  import-fields
  import-fks
  import-tables
  import-users
  table-transform
  user-transform]
 [resolve
  nested-owner
  collecting?
  export-in-bulk
  found-or-nil
  import-in-bulk
  lenient
  target-not-found
  target-not-found?
  visit-some])

(def batch-size
  "The number of rows, ids or entities that serialization queries or processes together."
  serialization.db/batch-size)

(def export-resolver
  "The export resolver backed by the application database."
  resolve.app-db/export-resolver)

(def lookup-resolver
  "The import resolver backed by the application database that only looks references up, creating nothing."
  resolve.app-db/lookup-resolver)

(def import-resolver
  "The import resolver backed by the application database, which creates the Users, Databases, Tables and Fields an
  import refers to but this instance lacks."
  resolve.app-db/import-resolver)
