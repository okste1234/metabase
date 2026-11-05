(ns metabase.serialization.db
  "Application database queries for the serialization module."
  (:require
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.util.malli :as mu]
   [metabase.util.malli.registry :as mr]
   [metabase.util.malli.schema :as ms]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def batch-size
  "The number of rows, ids or entities that serialization queries or processes together, which keeps every `:in`
  clause under database parameter limits."
  1000)

(mr/def ::row
  "A row of any serialized model, as its spec imports it; the model's own hooks and schemas check it."
  [:map {:closed false, ::mr/deliberately-open true, :description "row of a serialized model"}])

(mu/defn primary-key :- :keyword
  "The primary key column of `model`."
  [model :- :keyword]
  (first (t2/primary-keys model)))

(mu/defn rows-by-column
  "The `model` rows whose `column` is one of `values`."
  [model  :- :keyword
   column :- :keyword
   values :- [:sequential [:or :int :string]]]
  (t2/select model column [:in [:auto/param values]]))

(mu/defn field-values-by-ids
  "The `:id` and `field` of the `model` rows with `ids`."
  [model :- :keyword
   field :- :keyword
   ids   :- [:sequential ms/PositiveInt]]
  (t2/select [model :id field] :id [:in [:auto/param ids]]))

(mu/defn pks-by-entity-ids
  "The primary key `pk` and `:entity_id` of the `model` rows with `entity-ids`."
  [model      :- :keyword
   pk         :- :keyword
   entity-ids :- [:sequential :string]]
  (t2/select [model pk :entity_id] :entity_id [:in [:auto/param entity-ids]]))

(mu/defn pks-by-field-values
  "The primary key `pk` and `field` of the `model` rows whose `field` is one of `values`, oldest first."
  [model  :- :keyword
   pk     :- :keyword
   field  :- :keyword
   values :- [:sequential [:or :string :int :boolean]]]
  (t2/select [model pk field] field [:in [:auto/param values]] {:order-by [[pk :asc]]}))

(mu/defn tables-by-ids
  "The id, Database id, name, and schema of the Tables with `table-ids`."
  [table-ids :- [:sequential ::lib.schema.id/table]]
  (t2/select [:model/Table :id :db_id :name :schema] :id [:in [:auto/param table-ids]]))

(mu/defn databases-by-ids
  "The id and name of the Databases with `database-ids`."
  [database-ids :- [:sequential ::lib.schema.id/database]]
  (t2/select [:model/Database :id :name] :id [:in [:auto/param database-ids]]))

(mu/defn databases-by-names
  "The id and name of the Databases named one of `database-names`, oldest first."
  [database-names :- [:sequential :string]]
  (t2/select [:model/Database :id :name] :name [:in [:auto/param database-names]] {:order-by [[:id :asc]]}))

(mu/defn tables-by-names
  "The id, Database id, schema, and name of the Tables in the Databases with `database-ids` named one of
  `table-names`, oldest first."
  [database-ids :- [:sequential ::lib.schema.id/database]
   table-names  :- [:sequential :string]]
  (t2/select [:model/Table :id :db_id :schema :name]
             :db_id [:in [:auto/param database-ids]]
             :name  [:in [:auto/param table-names]]
             {:order-by [[:id :asc]]}))

(mu/defn fields-by-ids
  "The id, name, parent id, and Table id of the Fields with `field-ids`."
  [field-ids :- [:sequential ::lib.schema.id/field]]
  (t2/select [:model/Field :id :name :parent_id :table_id] :id [:in [:auto/param field-ids]]))

(mu/defn fields-by-names
  "The id, Table id, parent id, and name of the Fields in the Tables with `table-ids` named one of `field-names`,
  oldest first."
  [table-ids   :- [:sequential ::lib.schema.id/table]
   field-names :- [:sequential :string]]
  (t2/select [:model/Field :id :table_id :parent_id :name]
             :table_id [:in [:auto/param table-ids]]
             :name     [:in [:auto/param field-names]]
             {:order-by [[:id :asc]]}))

(mu/defn insert-inactive-user! :- ::lib.schema.id/user
  "Insert an inactive User with `email` and no password, and return its id."
  [email :- :string]
  (t2/insert-returning-pk! :model/User {:email email :is_active false}))

(mu/defn insert-stub-database! :- ::lib.schema.id/database
  "Insert a stub Postgres Database named `database-name` with empty connection details and return its id."
  [database-name :- :string]
  (t2/insert-returning-pk! :model/Database {:name                database-name
                                            :engine              :postgres
                                            :details             {}
                                            :is_stub             true
                                            :initial_sync_status "complete"}))

(mu/defn insert-inactive-table! :- ::lib.schema.id/table
  "Insert an inactive Table named `table-name` in `schema` of the Database with `database-id` and return its id."
  [database-id :- ::lib.schema.id/database
   schema      :- [:maybe :string]
   table-name  :- :string]
  (t2/insert-returning-pk! :model/Table {:db_id  database-id
                                         :schema schema
                                         :name   table-name
                                         :active false}))

(mu/defn insert-inactive-field! :- ::lib.schema.id/field
  "Insert an inactive, untyped Field named `field-name` under `parent-id` in the Table with `table-id` and return its
  id."
  [table-id   :- ::lib.schema.id/table
   parent-id  :- [:maybe ::lib.schema.id/field]
   field-name :- :string]
  (t2/insert-returning-pk! :model/Field {:table_id      table-id
                                         :parent_id     parent-id
                                         :name          field-name
                                         :active        false
                                         :base_type     :type/*
                                         :database_type "NULL"}))

(mu/defn insert-rows! :- [:sequential ::row]
  "Insert `rows` of `model`, running its hooks, and return the inserted instances."
  [model :- :keyword
   rows  :- [:sequential ::row]]
  (t2/insert-returning-instances! model rows))

(mu/defn update-row!
  "Update the `model` row whose primary key `pk` is `id` with `changes`, running the model's hooks."
  [model   :- :keyword
   pk      :- :keyword
   id      :- [:or :int :string]
   changes :- ::row]
  (t2/update! model pk [:auto/param id] changes))

(mu/defn delete-rows!
  "Delete the `model` rows whose primary key `pk` is one of `ids`, running the model's hooks."
  [model :- :keyword
   pk    :- :keyword
   ids   :- [:sequential [:or :int :string]]]
  (t2/delete! model pk [:in [:auto/param ids]]))

(mu/defn collections-by-entity-ids
  "The id, entity id, location, name, and namespace of the Collections with `eids`."
  [eids :- [:sequential :string]]
  (t2/select [:model/Collection :id :entity_id :location :name :namespace]
             :entity_id [:in [:auto/param eids]]))

(mu/defn collections-by-ids
  "The id, entity id, and name of the Collections with `ids`."
  [ids :- [:sequential ::lib.schema.id/collection]]
  (when (seq ids)
    (t2/select [:model/Collection :id :entity_id :name] :id [:in [:auto/param ids]])))

(mu/defn dashboard-names
  "The entity id and name of the Dashboards with `eids`."
  [eids :- [:sequential :string]]
  (t2/select [:model/Dashboard :entity_id :name] :entity_id [:in [:auto/param eids]]))

(mu/defn document-names
  "The entity id and name of the Documents with `eids`."
  [eids :- [:sequential :string]]
  (t2/select [:model/Document :entity_id :name] :entity_id [:in [:auto/param eids]]))

(mu/defn row-by-entity-id
  "The `model` row with `eid`, or nil."
  [model :- :keyword
   eid   :- :string]
  (t2/select-one model :entity_id eid))

(mu/defn metadata-tables
  "The `:metadata/table` rows named `table-name` in `schema` of the Database with `database-id`."
  [database-id :- ::lib.schema.id/database
   schema      :- [:maybe :string]
   table-name  :- :string]
  (t2/select :metadata/table :db_id database-id :schema schema :name table-name))

(mu/defn card-serdes-columns
  "The id, entity id, Collection id, and Database id of the Card with `card-id`, or nil."
  [card-id :- ::lib.schema.id/card]
  (t2/select-one [:model/Card :id :entity_id :collection_id :database_id] :id card-id))

(mu/defn measure-serdes-columns
  "The id, entity id, and Table id of the Measure with `measure-id`, or nil."
  [measure-id :- ::lib.schema.id/measure]
  (t2/select-one [:model/Measure :id :entity_id :table_id] :id measure-id))

(mu/defn segment-serdes-columns
  "The id, entity id, and Table id of the Segment with `segment-id`, or nil."
  [segment-id :- ::lib.schema.id/segment]
  (t2/select-one [:model/Segment :id :entity_id :table_id] :id segment-id))

(defn do-in-repeatable-read-transaction
  "Calls `thunk` in a REPEATABLE READ transaction of the app DB, so that every query it makes sees the same snapshot.
  Inside a transaction already open, which can't change its isolation without committing, `thunk` runs in that one."
  [thunk]
  (t2/with-connection [^java.sql.Connection conn]
    (if-not (.getAutoCommit conn)
      (thunk)
      (let [isolation (.getTransactionIsolation conn)]
        (.setTransactionIsolation conn java.sql.Connection/TRANSACTION_REPEATABLE_READ)
        (try
          (t2/with-transaction [_ conn]
            (thunk))
          (finally
            (.setTransactionIsolation conn isolation)))))))
