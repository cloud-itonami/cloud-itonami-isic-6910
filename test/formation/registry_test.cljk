(ns formation.registry-test
  "Conformance tests for `formation.registry` -- the LEI/ISO 7064 MOD 97-10
  arithmetic ported from `matsurigoto`'s corp-registry module
  (etzhayyim/root, ADR-2606062300); these tests are adapted from that
  module's own conformance suite so the port stays provably equivalent."
  (:require [clojure.test :refer [deftest is testing]]
            [formation.registry :as r]))

(deftest certificate-is-a-draft-not-a-real-filing
  (let [result (r/register-incorporation "Co" ["o"] 0 "art" "addr" "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest lei-roundtrip-validates
  (let [lei (r/assign-lei "OPER" "000000000001")]
    (is (= (count lei) 20))
    (is (r/validate-lei lei))))

(deftest lei-check-digits-make-mod97-one-for-many-entities
  (doseq [n (range 1 50)]
    (let [lei (r/assign-lei "OPER" (format "%012d" n))]
      (is (r/validate-lei lei) lei))))

(deftest lei-corruption-detected
  (let [lei (r/assign-lei "OPER" "000000000042")
        bad (vec lei)
        bad (assoc bad 8 (if (not= (nth bad 8) \Z) \Z \Y))]
    (is (r/validate-lei lei))
    (is (not (r/validate-lei (apply str bad))))))

(deftest lei-rejects-bad-length-and-chars
  (is (not (r/validate-lei "TOOSHORT")))
  (is (not (r/validate-lei "OPER00000000000001*9")))
  (is (not (r/validate-lei 12345))))

(deftest default-lei-does-not-collide-across-jurisdictions-at-the-same-sequence
  (testing "two entirely unrelated companies, each the Nth filing in ITS OWN jurisdiction
            (formation.store/next-sequence is per-jurisdiction, so this is the common case,
            not an edge case -- every jurisdiction's very first filing is sequence 0), must
            never be issued the same LEI just because they share a sequence number and no
            caller supplied an explicit entity-id12"
    (let [jpn-first (r/register-incorporation "Kotoba Trading GK" ["o-1"] 1000000 "art" "addr" "JPN" 0)
          gbr-first (r/register-incorporation "Totally Different Ltd" ["o-9"] 500000 "art2" "addr2" "GBR" 0)]
      (is (not= (get jpn-first "lei") (get gbr-first "lei")))
      (is (r/validate-lei (get jpn-first "lei")))
      (is (r/validate-lei (get gbr-first "lei")))))
  (testing "holds across a broader sweep of jurisdictions and sequence numbers, not just one pair"
    (let [combos (for [j ["JPN" "GBR" "DEU" "USA-DE" "EST" "KOR" "FRA" "BRA"]
                       s (range 5)]
                   (get (r/register-incorporation "X" ["o"] 1 "a" "ad" j s) "lei"))]
      (is (= (count combos) (count (distinct combos))) "no two distinct (jurisdiction, sequence) pairs share a LEI")
      (is (every? r/validate-lei combos)))))

(deftest incorporation-assigns-registry-number-and-lei
  (let [result (r/register-incorporation "Kotoba Trading GK" ["officer:tanaka"] 10000000
                                         "articles" "東京都" "JPN" 7)]
    (is (= (get result "registry_number") "JPN-00000007"))
    (is (r/validate-lei (get result "lei")))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "incorporation-draft"))))

(deftest incorporation-validation-rules
  (let [bad-args [["" ["o"] 0 "a" "ad"]
                  ["Co" [] 0 "a" "ad"]
                  ["Co" ["o"] -1 "a" "ad"]
                  ["Co" ["o"] 0 "" "ad"]
                  ["Co" ["o"] 0 "a" ""]]]
    (doseq [[name officers capital articles address] bad-args]
      (is (thrown? Exception
                   (r/register-incorporation name officers capital articles address "JPN" 1))))))

(deftest change-is-append-only
  (let [inc (r/register-incorporation "Co" ["o"] 0 "a" "ad" "JPN" 1)
        hist (r/append [] inc)
        chg (r/register-change (get inc "registry_number") {"address" "new"} "2026-07-03")
        hist2 (r/append hist chg)]
    (is (and (= (count hist) 1) (= (count hist2) 2)))
    (is (= (get-in hist2 [0 "kind"]) "incorporation-draft"))
    (is (= (get-in hist2 [1 "kind"]) "change-draft"))))

(deftest dissolution-is-append-only-and-preserves-history
  (let [inc (r/register-incorporation "Co" ["o"] 0 "a" "ad" "JPN" 2)
        hist (r/append [] inc)
        chg (r/register-change (get inc "registry_number") {"address" "new"} "2026-07-03")
        hist2 (r/append hist chg)
        dis (r/register-dissolution (get inc "registry_number") "voluntary wind-up" "2026-08-01")
        hist3 (r/append hist2 dis)]
    (is (= 3 (count hist3)))
    (is (= (get-in hist3 [0 "kind"]) "incorporation-draft"))
    (is (= (get-in hist3 [1 "kind"]) "change-draft"))
    (is (= (get-in hist3 [2 "kind"]) "dissolution-draft"))
    (is (= (get-in hist3 [2 "reason"]) "voluntary wind-up"))
    (is (= (get-in hist3 [0 "record_id"]) (get-in hist3 [2 "registry_number"]))
        "dissolution references the original incorporation's record_id (the registry_number) as ITS registry_number, never a new one")))

(deftest dissolution-validation-rules
  (is (thrown? Exception (r/register-dissolution "" "reason" "2026-08-01")))
  (is (thrown? Exception (r/register-dissolution "JPN-00000001" "" "2026-08-01"))))
