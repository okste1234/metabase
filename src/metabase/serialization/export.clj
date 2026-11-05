(ns metabase.serialization.export
  "Export: entities the caller selected, as portable maps, a batch at a time, in one snapshot of the app DB."
  (:require
   [metabase.serialization.db :as serialization.db]
   [metabase.serialization.extract :as serialization.extract]
   [metabase.serialization.resolve :as resolve]
   [metabase.serialization.resolve.app-db :as resolve.app-db]
   [metabase.util.log :as log]
   [toucan2.realize :as t2.realize]))

(set! *warn-on-reflection* true)

(defn- log-failure
  "`exported`, or nil when it is the error of a failed export, logged when it is a reference with no target, which
  only skips its entity, or when `continue-on-error`, and thrown otherwise."
  [continue-on-error exported]
  (if (instance? Exception exported)
    (let [^Exception e exported]
      (when-not (or (resolve/target-not-found? e) continue-on-error)
        (throw e))
      (log/warnf "Skipping %s: %s" (ex-message e) (some-> e ex-cause ex-message)))
    exported))

(defn export
  "A reducible of `objects`, rows of `model` the caller selected, exported as portable maps a batch at a time with
  `resolver`, the app DB one by default. An object whose reference has no target is left out and logged, and so is
  any other failing object when `continue-on-error`; any other failure throws."
  [model objects & {:keys [resolver continue-on-error] :or {resolver resolve.app-db/export-resolver}}]
  (eduction (comp (map t2.realize/realize)
                  (partition-all serialization.db/batch-size)
                  (mapcat #(serialization.extract/extract-batch model resolver (vec %)))
                  (keep #(log-failure continue-on-error %)))
            objects))

(defn export-batch
  "The `rows` of `model` exported with `resolver`, the app DB one by default, all their references resolved at once:
  a vector with, for each row, its exported map or the error that failed it."
  ([model rows]
   (export-batch model resolve.app-db/export-resolver rows))
  ([model resolver rows]
   (serialization.extract/extract-batch model resolver (mapv t2.realize/realize rows))))

(defn do-with-export-transaction
  "Calls `thunk`, which should do the whole export, reducing what it exports, in one REPEATABLE READ transaction, so
  the export is a consistent snapshot of the app DB. See [[with-export-transaction]]."
  [thunk]
  (serialization.db/do-in-repeatable-read-transaction thunk))

(defmacro with-export-transaction
  "Runs `body`, which should do the whole export, reducing what it exports, in one REPEATABLE READ transaction, so the
  export is a consistent snapshot of the app DB."
  [& body]
  `(do-with-export-transaction (fn [] ~@body)))
