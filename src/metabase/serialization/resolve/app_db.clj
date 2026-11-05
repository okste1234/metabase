(ns metabase.serialization.resolve.app-db
  "The resolvers serialization uses against this instance's application database. They look up each batch of
  references in one query, Field paths in one query per nesting level, and insert what an import refers to but this
  instance lacks one row at a time."
  (:require
   [metabase.serialization.db :as serialization.db]
   [metabase.serialization.resolve :as resolve]))

(set! *warn-on-reflection* true)

(defn- in-batches
  "The rows `f` returns for each batch of the distinct `xs`."
  [f xs]
  (into [] (comp (partition-all serialization.db/batch-size) (mapcat #(f (vec %)))) (distinct xs)))

(defn- in-batch-pairs
  "The rows `f` returns for each pair of batches of the distinct `xs` and `ys`."
  [f xs ys]
  (vec (for [x-batch (partition-all serialization.db/batch-size (distinct xs))
             y-batch (partition-all serialization.db/batch-size (distinct ys))
             row     (f (vec x-batch) (vec y-batch))]
         row)))

(defn index-first
  "The map from `(key-fn row)` to `(val-fn row)` for `rows`, keeping the first row of each key."
  [key-fn val-fn rows]
  (reduce (fn [m row]
            (let [k (key-fn row)]
              (cond-> m
                (not (contains? m k)) (assoc k (val-fn row)))))
          {}
          rows))

(defn- missing-inputs
  "The distinct `inputs` that `found` has no entry for."
  [found inputs]
  (into [] (comp (distinct) (remove #(contains? found %))) inputs))

;;; ------------------------------------------------------ Export ------------------------------------------------------

(defn- field-values [model field ids]
  (into {} (map (juxt :id field)) (in-batches #(serialization.db/field-values-by-ids model field %) ids)))

(defn- table-refs [table-ids]
  (let [tables   (in-batches serialization.db/tables-by-ids table-ids)
        db-names (into {} (map (juxt :id :name)) (in-batches serialization.db/databases-by-ids (map :db_id tables)))]
    (into {}
          (map (fn [{:keys [id db_id schema name]}] [id [(db-names db_id) schema name]]))
          tables)))

(defn- fields-with-ancestors
  "The map from id to row of the Fields with `field-ids` and all their ancestors, one nesting level per query."
  [field-ids]
  (loop [rows {} pending (distinct field-ids)]
    (if (empty? pending)
      rows
      (let [fetched (in-batches serialization.db/fields-by-ids pending)
            rows    (into rows (map (juxt :id identity)) fetched)]
        (recur rows (into [] (comp (keep :parent_id) (remove rows) (distinct)) fetched))))))

(defn- ancestry
  "The rows of the Field with `field-id` and its ancestors in `rows`, root first."
  [rows field-id]
  (loop [id field-id chain ()]
    (if-let [row (get rows id)]
      (recur (:parent_id row) (conj chain row))
      (vec chain))))

(defn- field-refs [field-ids]
  (let [rows   (fields-with-ancestors field-ids)
        chains (into {} (keep (fn [id] (when (contains? rows id) [id (ancestry rows id)]))) (distinct field-ids))
        tables (table-refs (map (comp :table_id first) (vals chains)))]
    (into {}
          (keep (fn [[id chain]]
                  (when-let [table-ref (get tables (:table_id (first chain)))]
                    [id (into table-ref (map :name) chain)])))
          chains)))

(defn- entity-ids [model ids]
  (let [eids (into {} (keep (fn [[id eid]] (when eid [id eid]))) (field-values model :entity_id ids))]
    (if-let [[owner owner-key] (resolve/nested-owner model)]
      (let [owner-ids  (field-values model owner-key (keys eids))
            owner-eids (entity-ids owner (vals owner-ids))]
        (into {}
              (keep (fn [[id eid]] (when-let [owner-eid (get owner-eids (get owner-ids id))] [id [owner-eid eid]])))
              eids))
      eids)))

(def export-resolver
  "The export resolver backed by the application database."
  (reify resolve/ExportResolver
    (entity-ids   [_ model ids]       (entity-ids model ids))
    (field-values [_ model field ids] (field-values model field ids))
    (table-refs   [_ table-ids]       (table-refs table-ids))
    (field-refs   [_ field-ids]       (field-refs field-ids))))

;;; ------------------------------------------------------ Import ------------------------------------------------------

(defn- entity-id-of
  "The entity id a portable id names: itself, or the last element of a nested model's path ids."
  [eid]
  (if (vector? eid) (last eid) eid))

(defn- entity-pks [model eids]
  (let [pk    (serialization.db/primary-key model)
        found (index-first :entity_id pk (in-batches #(serialization.db/pks-by-entity-ids model pk %)
                                                     (map entity-id-of eids)))]
    (into {}
          (keep (fn [eid] (when-some [id (get found (entity-id-of eid))] [eid id])))
          eids)))

(defn- pks-by-field [model field values]
  (let [pk (serialization.db/primary-key model)]
    (index-first field pk (in-batches #(serialization.db/pks-by-field-values model pk field %) values))))

(defn- user-ids [create? emails]
  (let [found (pks-by-field :model/User :email emails)]
    (cond-> found
      create? (into (map (fn [email] [email (serialization.db/insert-inactive-user! email)]))
                    (missing-inputs found emails)))))

(defn- database-ids [create? db-names]
  (let [found (index-first :name :id (in-batches serialization.db/databases-by-names db-names))]
    (cond-> found
      create? (into (map (fn [db-name] [db-name (serialization.db/insert-stub-database! db-name)]))
                    (missing-inputs found db-names)))))

(defn- table-ids [create? paths]
  (let [paths  (distinct paths)
        db-ids (database-ids create? (map first paths))
        found  (index-first (juxt :db_id :schema :name)
                            :id
                            (in-batch-pairs serialization.db/tables-by-names (vals db-ids) (map #(nth % 2) paths)))]
    (into {}
          (keep (fn [[db-name schema table-name :as path]]
                  (let [db-id (get db-ids db-name)]
                    (when-let [id (or (get found [db-id schema table-name])
                                      (when (and create? db-id)
                                        (serialization.db/insert-inactive-table! db-id schema table-name)))]
                      [path id]))))
          paths)))

(defn- import-field-path
  "The `[index field-id]` of the Field at `field-names` in the Table with `table-id`, adding the missing nodes to
  `index`, a map from `[table-id parent-id name]` to Field id, as new inactive Fields when `create?`, or nil."
  [create? index table-id field-names]
  (reduce (fn [[index parent-id] field-name]
            (let [k [table-id parent-id field-name]]
              (cond
                (contains? index k) [index (get index k)]
                (and create? table-id (or parent-id (= field-name (first field-names))))
                (let [field-id (serialization.db/insert-inactive-field! table-id parent-id field-name)]
                  [(assoc index k field-id) field-id])
                :else (reduced [index nil]))))
          [index nil]
          field-names))

(defn- table-path [field-path]
  (subvec (vec field-path) 0 3))

(defn- field-ids [create? paths]
  (let [paths     (distinct paths)
        table-ids (table-ids create? (map table-path paths))
        index     (index-first (juxt :table_id :parent_id :name)
                               :id
                               (in-batch-pairs serialization.db/fields-by-names
                                               (vals table-ids)
                                               (mapcat #(drop 3 %) paths)))]
    (second
     (reduce (fn [[index found] path]
               (let [[index field-id] (import-field-path create? index (get table-ids (table-path path)) (drop 3 path))]
                 [index (cond-> found field-id (assoc path field-id))]))
             [index {}]
             paths))))

(defn- app-db-import-resolver
  "An import resolver backed by the application database, which creates the Users, Databases, Tables and Fields an
  import refers to but this instance lacks when `create?`."
  [create?]
  (reify resolve/ImportResolver
    (entity-pks   [_ model eids]         (entity-pks model eids))
    (pks-by-field [_ model field values] (pks-by-field model field values))
    (user-ids     [_ emails]             (user-ids create? emails))
    (database-ids [_ db-names]           (database-ids create? db-names))
    (table-ids    [_ paths]              (table-ids create? paths))
    (field-ids    [_ paths]              (field-ids create? paths))))

(def import-resolver
  "The import resolver backed by the application database. It creates the Users, Databases, Tables and Fields that an
  import refers to but this instance lacks."
  (app-db-import-resolver true))

(def lookup-resolver
  "The import resolver backed by the application database that only looks references up, leaving out the ones with
  no target instead of creating it."
  (app-db-import-resolver false))
