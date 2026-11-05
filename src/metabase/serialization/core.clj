(ns metabase.serialization.core
  "Serialization (SerDes v3): exporting entities into portable maps and importing them back, in bulk.

  Exported entities refer to each other by portable references rather than database ids; a resolver (see
  [[metabase.serialization.resolve]]) translates the references of a whole batch at once."
  (:refer-clojure :exclude [import load])
  (:require
   [metabase.serialization.fk :as serialization.fk]
   [metabase.serialization.resolve :as resolve]
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
