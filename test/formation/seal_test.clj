(ns formation.seal-test
  "Seal craft wire: valid `:seal/compose` auto-commits (hash + metadata
  on SSoT + ledger); empty name / unsupported kind HARD-hold without
  writing a seal. Coordinates come only from inkan."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [formation.store :as store]
            [formation.operation :as op]
            [formation.seal :as seal]
            [inkan.geometry :as igeom]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(defn- exec-op [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(deftest supported-kinds-come-only-from-inkan
  (is (= (into #{} (map :kind igeom/kinds)) seal/supported-kinds))
  (is (contains? seal/supported-kinds :round-corporate))
  (is (not (contains? seal/supported-kinds :hexagon-fancy))))

(deftest validate-spec-hard-rules
  (is (some #{:empty-seal-text}
            (map :rule (seal/validate-spec {:kind :round-vertical :text ""}))))
  (is (some #{:empty-seal-text}
            (map :rule (seal/validate-spec {:kind :round-vertical :text "   "}))))
  (is (some #{:unsupported-seal-kind}
            (map :rule (seal/validate-spec {:kind :not-a-kind :text "山田"}))))
  (is (empty? (seal/validate-spec {:kind :round-vertical :text "山田太郎"}))))

(deftest compose-returns-hash-and-svg-from-inkan
  (let [r (seal/compose {:kind :round-corporate
                         :text "株式会社コトバ商事"
                         :inner-text "代表取締役之印"
                         :size-mm 18.0})]
    (is (:ok? r))
    (is (string? (:svg r)))
    (is (str/includes? (:svg r) "<svg")
        "inkan produced an SVG string")
    (is (= 64 (count (get-in r [:metadata :svg-hash]))))
    (is (= "inkan.svg/seal" (get-in r [:metadata :source])))
    (is (= :round-corporate (get-in r [:metadata :kind])))))

(deftest valid-compose-auto-commits-with-hash-on-ledger
  (testing "governor-clean seal/compose auto-commits at phase 3; SSoT + ledger carry hash/metadata"
    (let [[db actor] (fresh)
          req {:op :seal/compose
               :subject "seal-1"
               :spec {:kind :round-corporate
                      :text "株式会社コトバ商事"
                      :inner-text "代表取締役之印"
                      :size-mm 18.0}}
          res (exec-op actor "seal-ok" req)
          rec (store/seal-of db "seal-1")
          led (store/ledger db)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (some? rec) "seal written to SSoT")
      (is (= :round-corporate (:kind rec)))
      (is (= "株式会社コトバ商事" (:text rec)))
      (is (string? (:svg-hash rec)))
      (is (= 64 (count (:svg-hash rec))))
      (is (string? (:svg rec)) "SVG retained for package export")
      (is (= 1 (count led)))
      (is (= :commit (:disposition (first led))))
      (is (= :seal/compose (:op (first led)))))))

(deftest empty-name-is-hard-held
  (testing "empty seal text -> HARD hold, no seal written, ledger records hold basis"
    (let [[db actor] (fresh)
          res (exec-op actor "seal-empty"
                       {:op :seal/compose
                        :subject "seal-empty"
                        :spec {:kind :round-vertical :text ""}})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/seal-of db "seal-empty")) "no SSoT write on hold")
      (is (some #{:empty-seal-text} (-> (store/ledger db) first :basis))))))

(deftest unsupported-kind-is-hard-held
  (testing "kind not in inkan.geometry/kinds -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "seal-kind"
                       {:op :seal/compose
                        :subject "seal-bad-kind"
                        :spec {:kind :hexagon-fancy :text "山田太郎"}})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (nil? (store/seal-of db "seal-bad-kind")))
      (is (some #{:unsupported-seal-kind} (-> (store/ledger db) first :basis))))))

(deftest attach-after-compose-links-application
  (testing "compose then attach links seal-id onto the application"
    (let [[db actor] (fresh)
          _ (exec-op actor "c1"
                     {:op :seal/compose :subject "seal-app-1"
                      :spec {:kind :square-1 :text "コトバ之印" :size-mm 21.0}})
          res (exec-op actor "a1"
                       {:op :seal/attach :subject "app-1" :seal-id "seal-app-1"})]
      (is (= :escalate (get-in res [:state :disposition]))
          "attach is not in phase-3 :auto -- escalates for human approval")
      (let [r2 (g/run* actor {:approval {:status :approved :by "op-1"}}
                       {:thread-id "a1" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "seal-app-1" (:seal-id (store/application db "app-1"))))
        (is (some? (store/seal-of db "seal-app-1")))))))

(deftest attach-unknown-seal-is-hard-held
  (let [[db actor] (fresh)
        res (exec-op actor "a-missing"
                     {:op :seal/attach :subject "app-1" :seal-id "no-such-seal"})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (nil? (:seal-id (store/application db "app-1"))))
    (is (some #{:seal-attach-unknown-seal} (-> (store/ledger db) first :basis)))))
