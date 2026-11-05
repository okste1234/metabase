(ns metabase.serialization.resolve
  "Resolution of the references serialization replaces, in bulk.

  An exported entity refers to other entities by portable references instead of database ids: a Card by its
  `entity_id`, a Table by `[db-name schema table-name]`, a Field by `[db-name schema table-name & field-names]`, a
  User by email, a Database by name. A resolver turns collections of database ids into such references on export,
  and back on import, a whole collection per call.

  A reference to resolve is a vector naming what to resolve, its input last:

  - export: `[:fk model id]`, `[:fk-keyed model field id]`, `[:table id]`, `[:field id]`;
  - import: `[:fk model eid]`, `[:fk-keyed model field value]`, `[:user email]`, `[:database db-name]`,
    `[:table path]`, `[:field path]`.

  This namespace has no app DB dependency, so it serves resolvers backed by anything.")

(set! *warn-on-reflection* true)

(defmulti nested-owner
  "The `[owner-model owner-key]` of `model` when exports name its entities by `[owner-entity-id entity-id]`, or nil."
  {:arglists '([model])}
  identity)

(defmethod nested-owner :default [_] nil)

(defprotocol ExportResolver
  "Resolves collections of database ids to portable references. Each method returns a map from every input it found
  to its reference, leaving out the inputs with no target unless the implementation says otherwise."
  (entity-ids   [resolver model ids]       "The entity ids of the `model` rows with `ids`.")
  (field-values [resolver model field ids] "The `field` values of the `model` rows with `ids`.")
  (table-refs   [resolver table-ids]       "The `[db-name schema table-name]` references of the Tables with `table-ids`.")
  (field-refs   [resolver field-ids]       "The `[db-name schema table-name & field-names]` references of the Fields with `field-ids`."))

(defprotocol ImportResolver
  "Resolves collections of portable references to database ids. Each method returns a map from every input it found
  to its id, leaving out the inputs with no target."
  (entity-pks   [resolver model eids]         "The primary keys of the `model` rows with entity ids `eids`; a nested entity's id is the vector of its owner's entity id and its own, or its own entity id alone.")
  (pks-by-field [resolver model field values] "The primary keys of the `model` rows whose `field` is one of `values`.")
  (user-ids     [resolver emails]             "The ids of the Users with `emails`, creating inactive Users for the missing emails.")
  (database-ids [resolver db-names]           "The ids of the Databases named `db-names`, creating stub Databases for the missing names.")
  (table-ids    [resolver paths]              "The ids of the Tables at `[db-name schema table-name]` `paths`, creating inactive Tables for the missing paths.")
  (field-ids    [resolver paths]              "The ids of the Fields at `[db-name schema table-name & field-names]` `paths`, creating inactive Fields for the missing paths."))

(defprotocol ResolverOptions
  "A resolver that carries options for the walks it resolves the references of."
  (resolver-options [resolver] "The options of `resolver`."))

(defn options
  "The options of `resolver`, which the walks it serves read (see [[option]]):

  - `:numeric-ids-allowed?`, whether bare numeric table, field and content ids are accepted alongside portable
    references in an imported form. False by default, the safe direction: a numeric id is left alone rather than
    resolved against whatever row carries it. A surface that may author bare ids sets it, and its resolver re-derives
    the guarantees a portable reference gets from resolving a name (in this database, visible, readable).
  - `:skip-schema-validation?`, whether an imported query is kept even when this instance's query schema rejects it."
  [resolver]
  (if (satisfies? ResolverOptions resolver) (resolver-options resolver) {}))

(defn with-options
  "`resolver`, an [[ExportResolver]] or an [[ImportResolver]], with the [[options]] `opts` merged into its own."
  [resolver opts]
  (let [opts (merge (options resolver) opts)]
    (reify
      ResolverOptions
      (resolver-options [_] opts)
      ExportResolver
      (entity-ids [_ model ids] (entity-ids resolver model ids))
      (field-values [_ model field ids] (field-values resolver model field ids))
      (table-refs [_ table-ids] (table-refs resolver table-ids))
      (field-refs [_ field-ids] (field-refs resolver field-ids))
      ImportResolver
      (entity-pks [_ model eids] (entity-pks resolver model eids))
      (pks-by-field [_ model field values] (pks-by-field resolver model field values))
      (user-ids [_ emails] (user-ids resolver emails))
      (database-ids [_ db-names] (database-ids resolver db-names))
      (table-ids [_ paths] (table-ids resolver paths))
      (field-ids [_ paths] (field-ids resolver paths)))))

(defn- grouped-refs
  "The `[kind inputs]` pairs of `refs`, a kind being a reference without its input, in order of first appearance."
  [refs]
  (let [refs   (distinct refs)
        groups (group-by pop refs)]
    (for [kind (distinct (map pop refs))]
      [kind (mapv peek (get groups kind))])))

(defn- resolve-refs
  "The map from each of `refs` to what `resolve-kind`, a fn of a kind and its inputs, finds for it."
  [resolve-kind refs]
  (into {}
        (mapcat (fn [[kind inputs]]
                  (let [found (resolve-kind kind inputs)]
                    (for [input inputs
                          :when (contains? found input)]
                      [(conj kind input) (get found input)]))))
        (grouped-refs refs)))

(defn export-refs
  "The map from each of the export `refs` to what `resolver` resolves it to, leaving out the refs with no target, with
  one resolver call per kind of reference."
  [resolver refs]
  (resolve-refs (fn [[k model field] inputs]
                  (case k
                    :fk       (entity-ids resolver model inputs)
                    :fk-keyed (field-values resolver model field inputs)
                    :table    (table-refs resolver inputs)
                    :field    (field-refs resolver inputs)))
                refs))

(defn import-refs
  "The map from each of the import `refs` to what `resolver` resolves it to, leaving out the refs with no target, with
  one resolver call per kind of reference."
  [resolver refs]
  (resolve-refs (fn [[k model field] inputs]
                  (case k
                    :fk       (entity-pks resolver model inputs)
                    :fk-keyed (pks-by-field resolver model field inputs)
                    :user     (user-ids resolver inputs)
                    :database (database-ids resolver inputs)
                    :table    (table-ids resolver inputs)
                    :field    (field-ids resolver inputs)))
                refs))

(defn target-not-found
  "The error of an export or import reference `ref` whose target does not exist."
  [[k model :as ref]]
  (let [input        (peek ref)
        target-model (case k
                       (:fk :fk-keyed) model
                       :table          :model/Table
                       :field          :model/Field
                       :user           :model/User
                       :database       :model/Database)]
    (ex-info (format "%s %s was not found" (name target-model) (pr-str input))
             {:model target-model
              :id    input
              ::type :target-not-found})))

(defn target-not-found?
  "Whether `e` is a [[target-not-found]] error."
  [e]
  (= :target-not-found (::type (ex-data e))))

(defn ref-value
  "The value `resolved` holds for `ref`: nil for a missing keyed lookup, and a [[target-not-found]] error for any other
  missing target."
  [resolved [k :as ref]]
  (cond
    (contains? resolved ref) (get resolved ref)
    (= k :fk-keyed)          nil
    :else                    (throw (target-not-found ref))))

(defn found-or-nil
  "What `f` returns, or nil when it throws a [[target-not-found]] error."
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (when-not (target-not-found? e)
        (throw e)))))

(defn lenient
  "`resolver`, an [[ImportResolver]], resolving a reference with no target to nil instead of failing on it."
  [resolver]
  (let [with-nils (fn [found inputs] (into found (map (fn [x] [x nil])) (remove #(contains? found %) inputs)))]
    (reify ImportResolver
      (entity-pks [_ model eids] (with-nils (entity-pks resolver model eids) eids))
      (pks-by-field [_ model field values] (with-nils (pks-by-field resolver model field values) values))
      (user-ids [_ emails] (with-nils (user-ids resolver emails) emails))
      (database-ids [_ db-names] (with-nils (database-ids resolver db-names) db-names))
      (table-ids [_ paths] (with-nils (table-ids resolver paths) paths))
      (field-ids [_ paths] (with-nils (field-ids resolver paths) paths)))))

(defn walk-in-bulk
  "Each of `xs` rewritten by `walk`, a fn of a visitor and a form that replaces every reference in the form with what
  the visitor returns for it, and `opts` the [[options]] the walk reads with [[option]]. A first walk collects the references, returning each input unresolved; `resolve`, a fn
  of a collection of references like [[export-refs]], resolves them all at once; and a second walk turns each
  resolution into a value with [[ref-value]]. A reference the first walk misses, because it threw or took a path that
  depends on resolved values, is resolved when the second walk meets it."
  ([walk resolve xs]
   (walk-in-bulk walk resolve {} xs))
  ([walk resolve opts xs]
   (let [collected (volatile! [])
         collect   (with-meta (fn [ref] (vswap! collected conj ref) (peek ref)) {::collecting true ::options opts})
         _         (run! #(try (walk collect %) (catch Exception _ nil)) xs)
         refs      (distinct @collected)
         resolved  (volatile! (resolve refs))
         tried     (volatile! (set refs))
         visit     (with-meta (fn [ref]
                                (when-not (contains? @tried ref)
                                  (vswap! tried conj ref)
                                  (vswap! resolved merge (resolve [ref])))
                                (ref-value @resolved ref))
                              {::options opts})]
     (mapv #(walk visit %) xs))))

(defn export-in-bulk
  "Each of `xs` rewritten by `walk` (see [[walk-in-bulk]]), with the export references resolved by `resolver`."
  [resolver walk xs]
  (walk-in-bulk walk #(export-refs resolver %) (options resolver) xs))

(defn import-in-bulk
  "Each of `xs` rewritten by `walk` (see [[walk-in-bulk]]), with the import references resolved by `resolver`."
  [resolver walk xs]
  (walk-in-bulk walk #(import-refs resolver %) (options resolver) xs))

(defn option
  "The value of the option `k` (see [[options]]) of the resolver whose references `visit`, a visitor of
  [[walk-in-bulk]], resolves."
  [visit k]
  (get (::options (meta visit)) k))

(defn collecting?
  "Whether `visit` is the visitor of the first walk of [[walk-in-bulk]], which returns each input unresolved, so a
  walk can skip the steps that need resolved values."
  [visit]
  (boolean (::collecting (meta visit))))

(defn visit-some
  "What `visit` returns for `ref`, or nil when the input of `ref` is nil."
  [visit ref]
  (when (some? (peek ref))
    (visit ref)))
