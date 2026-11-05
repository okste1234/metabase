(ns metabase.serialization.resolve-test
  (:require
   [clojure.test :refer :all]
   [metabase.serialization.resolve :as resolve]))

(defn- recording-resolver
  "An export resolver that names each Card id `Card_<id>`, records every call in `calls`, and finds no Card 404."
  [calls]
  (reify resolve/ExportResolver
    (entity-ids [_ model ids]
      (swap! calls conj [:entity-ids model (vec ids)])
      (into {} (keep (fn [id] (when-not (= id 404) [id (str (name model) "_" id)]))) ids))
    (field-values [_ model field ids]
      (swap! calls conj [:field-values model field (vec ids)])
      (into {} (map (fn [id] [id (str "user" id "@example.com")])) ids))
    (table-refs [_ ids]
      (swap! calls conj [:table-refs (vec ids)])
      (into {} (map (fn [id] [id ["DB" nil (str "T" id)]])) ids))
    (field-refs [_ ids]
      (swap! calls conj [:field-refs (vec ids)])
      (into {} (map (fn [id] [id ["DB" nil "T" (str "F" id)]])) ids))))

(deftest ^:parallel export-refs-test
  (testing "resolves each kind of reference with one call, leaving out the references with no target"
    (let [calls (atom [])]
      (is (= {[:fk :model/Card 1]                  "Card_1"
              [:fk :model/Card 2]                  "Card_2"
              [:fk-keyed :model/User :email 7]     "user7@example.com"
              [:table 3]                           ["DB" nil "T3"]}
             (resolve/export-refs (recording-resolver calls)
                                  [[:fk :model/Card 1] [:table 3] [:fk :model/Card 2] [:fk :model/Card 1]
                                   [:fk-keyed :model/User :email 7] [:fk :model/Card 404]])))
      (is (= [[:entity-ids :model/Card [1 2 404]]
              [:table-refs [3]]
              [:field-values :model/User :email [7]]]
             @calls)))))

(deftest ^:parallel ref-value-test
  (let [resolved {[:fk :model/Card 1] "Card_1"}]
    (is (= "Card_1" (resolve/ref-value resolved [:fk :model/Card 1])))
    (testing "missing keyed lookup is nil"
      (is (nil? (resolve/ref-value resolved [:fk-keyed :model/User :email 2]))))
    (testing "any other missing target throws target-not-found error"
      (is (resolve/target-not-found?
           (try (resolve/ref-value resolved [:fk :model/Card 2])
                nil
                (catch clojure.lang.ExceptionInfo e e))))
      (is (=? {:model :model/Table :id 9}
              (ex-data (try (resolve/ref-value resolved [:table 9])
                            nil
                            (catch clojure.lang.ExceptionInfo e e))))))))

(deftest ^:parallel walk-in-bulk-test
  (testing "rewrites every form with one resolution of all their references"
    (let [calls (atom [])
          walk  (fn [visit ids] (mapv #(resolve/visit-some visit [:fk :model/Card %]) ids))]
      (is (= [["Card_1" nil] ["Card_2" "Card_1"]]
             (resolve/walk-in-bulk walk #(resolve/export-refs (recording-resolver calls) %) [[1 nil] [2 1]])))
      (is (= [[:entity-ids :model/Card [1 2]]] @calls))))
  (testing "resolves a reference the collecting walk missed because it threw before reaching it"
    (let [calls (atom [])
          walk  (fn [visit [a b]]
                  (let [x (visit [:fk :model/Card a])]
                    (when-not (string? x)
                      (throw (ex-info "needs a resolved value" {})))
                    [x (visit [:fk :model/Card b])]))]
      (is (= [["Card_1" "Card_2"] ["Card_3" "Card_4"]]
             (resolve/walk-in-bulk walk #(resolve/export-refs (recording-resolver calls) %) [[1 2] [3 4]])))
      (is (= [[:entity-ids :model/Card [1 3]]
              [:entity-ids :model/Card [2]]
              [:entity-ids :model/Card [4]]]
             @calls))))
  (testing "a missing target fails only the form that refers to it"
    (let [walk (fn [visit id]
                 (try (visit [:fk :model/Card id])
                      (catch clojure.lang.ExceptionInfo e (ex-message e))))]
      (is (= ["Card_1" "Card 404 was not found"]
             (resolve/walk-in-bulk walk #(resolve/export-refs (recording-resolver (atom [])) %) [1 404]))))))
