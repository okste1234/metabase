(ns metabase.serialization.core
  "Serialization (SerDes v3): exporting entities into portable maps and importing them back, in bulk.

  Exported entities refer to each other by portable references rather than database ids; a resolver (see
  [[metabase.serialization.resolve]]) translates the references of a whole batch at once."
  (:refer-clojure :exclude [import load])
  (:require
   [metabase.serialization.db :as serialization.db]
   [metabase.serialization.export :as serialization.export]
   [metabase.serialization.extract :as serialization.extract]
   [metabase.serialization.fk :as serialization.fk]
   [metabase.serialization.mbql :as serialization.mbql]
   [metabase.serialization.parameters :as serialization.parameters]
   [metabase.serialization.portable :as serialization.portable]
   [metabase.serialization.resolve :as resolve]
   [metabase.serialization.resolve.app-db :as resolve.app-db]
   [metabase.serialization.spec :as serialization.spec]
   [metabase.serialization.storage :as serialization.storage]
   [metabase.serialization.visualization-settings :as serialization.viz]
   [metabase.util.namespaces :as shared.ns]))

(shared.ns/import-fns
 [serialization.export
  export
  export-batch
  with-export-transaction]
 [serialization.extract
  export-owned]
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
 [serialization.spec
  columns
  date-transform
  import-only
  keyword-transform
  spec
  skip-all
  spec-of
  unchanged]
 [resolve
  nested-owner
  collecting?
  export-in-bulk
  found-or-nil
  import-in-bulk
  lenient
  target-not-found
  target-not-found?
  visit-some]
 [serialization.mbql
  card-entity-id
  card-template-tag-export-rename
  card-template-tag-import-rename
  card-template-tag-renames
  export-mbql
  import-mbql
  mbql-transform
  recording-card-entity-ids
  serialized-query-source-table
  walk-export
  walk-import
  walk-export-refs
  walk-import-refs]
 [serialization.parameters
  export-parameter-mappings
  export-parameters
  import-parameter-mappings
  import-parameters
  parameter-mappings-transform
  parameters-transform]
 [serialization.viz
  export-visualization-settings
  import-visualization-settings
  import-visualizer-settings
  link-card-model->toucan-model
  visualization-settings-transform]
 [serialization.storage
  collection-namespace-folder
  resource-paths
  storable
  storage-default-collection-path
  batch-storage-paths
  storage-path-prefixes
  storage-paths]
 [serialization.portable
  key-of
  log-str
  model-of
  portable-id
  restore-identity])

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

(def skip
  "A transformed value that leaves its key out of the result."
  serialization.spec/skip)
