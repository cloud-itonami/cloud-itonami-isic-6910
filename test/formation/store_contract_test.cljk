(ns formation.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `talent.store-contract-test` /
  `itonami.store-contract-test` for the same pattern on the other two
  actors in this family."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Kotoba Trading GK" (:entity-name (store/application s "app-1"))))
      (is (= "JPN" (:jurisdiction (store/application s "app-1"))))
      (is (= ["o-1"] (:officers (store/application s "app-1"))))
      (is (= "田中 一郎" (:name (store/officer s "o-1"))))
      (is (false? (:sanctions-hit? (store/officer s "o-1"))))
      (is (true? (:sanctions-hit? (store/officer s "o-2"))))
      (is (= ["app-1" "app-2"] (mapv :id (store/all-applications s))))
      (is (nil? (store/kyc-of s "o-1")))
      (is (nil? (store/assessment-of s "app-1")))
      (is (nil? (store/seal-of s "seal-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/registry-history s)))
      (is (zero? (store/next-sequence s "JPN"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert
                                 :value {:id "app-1" :status :ready}})
        (is (= :ready (:status (store/application s "app-1"))))
        (is (= "Kotoba Trading GK" (:entity-name (store/application s "app-1"))) "name preserved"))
      (testing "assessment / kyc payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["app-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "app-1")))
        (store/commit-record! s {:effect :kyc/set :path ["o-1"]
                                 :payload {:officer-id "o-1" :verdict :clear}})
        (is (= {:officer-id "o-1" :verdict :clear} (store/kyc-of s "o-1"))))
      (testing "filing drafts a registry record and advances the sequence"
        (store/commit-record! s {:effect :filing/mark-submitted :path ["app-1"]})
        ;; registry-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose registry-number key is "record_id".
        (is (= "JPN-00000000" (get (first (store/registry-history s)) "record_id")))
        (is (= "incorporation-draft" (get (first (store/registry-history s)) "kind")))
        (is (= :filed (:status (store/application s "app-1"))))
        (is (= 1 (count (store/registry-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))
      (testing "seal craft metadata commits and attaches to an application"
        (store/commit-record! s {:effect :seal/set :path ["seal-1"]
                                 :payload {:seal-id "seal-1" :kind :round-vertical
                                           :text "山田" :svg-hash "abc" :source "inkan.svg/seal"}})
        (is (= "山田" (:text (store/seal-of s "seal-1"))))
        (is (= "abc" (:svg-hash (store/seal-of s "seal-1"))))
        (store/commit-record! s {:effect :seal/attach :path ["app-1"]
                                 :value {:seal-id "seal-1"}})
        (is (= "seal-1" (:seal-id (store/application s "app-1"))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/application s "nope")))
    (is (= [] (store/all-applications s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/registry-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-applications s {"x" {:id "x" :entity-name "X" :jurisdiction "JPN"
                                     :officers [] :capital 0 :articles "a" :address "b"
                                     :status :intake}})
    (is (= "X" (:entity-name (store/application s "x"))))))
