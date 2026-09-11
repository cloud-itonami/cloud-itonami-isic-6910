(ns formation.governor-contract-test
  "The governor contract as executable tests -- the company-formation
  analog of robotaxi's safety_contract_test / gftd-talent-actor's
  policy_contract_test. The single invariant under test:

    Registrar-LLM never files/pays a record the RegistrarGovernor would
    reject, `:filing/submit` NEVER auto-commits at any phase, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [formation.store :as store]
            [formation.operation :as op]))

(defn- fresh
  "A fresh [db actor] pair. Defaults to MemStore; pass `store/datomic-
  seed-db` to run the SAME actor flow against DatomicStore instead --
  every other test in this namespace exercises the governor contract
  through MemStore only. `store_contract_test.clj` proves raw CRUD parity
  between the two backends, but that alone does not prove the FULL actor
  flow (advisor -> governor -> phase -> commit, including every hold
  path) behaves identically -- see the `*-on-datomic-store-too` tests
  below for that cross-backend proof on the highest-stakes invariants."
  ([] (fresh store/seed-db))
  ([db-ctor]
   (let [db (db-ctor)]
     [db (op/build db)])))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :application/intake :subject "app-1"
                   :patch {:id "app-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/application db "app-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "op-1"}}
                       {:thread-id "t2" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "app-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "app-1")) "no assessment written"))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "a sanctions/PEP hit on an officer -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :kyc/screen :subject "o-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "o-2")) "no KYC clearance written"))))

(deftest filing-without-assessment-is-held
  (testing "filing/submit before any jurisdiction assessment -> HOLD (incomplete documents)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :filing/submit :subject "app-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:incomplete-documents} (-> (store/ledger db) first :basis))))))

(deftest filing-without-any-kyc-screening-is-held
  (testing "assessment is clean, but NO officer was ever screened -> HOLD (kyc-incomplete), not a silent pass"
    (let [[db actor] (fresh)
          _ (exec-op actor "t5b-a" {:op :jurisdiction/assess :subject "app-1"} operator)
          _ (approve! actor "t5b-a")
          res (exec-op actor "t5b" {:op :filing/submit :subject "app-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:kyc-incomplete} (-> (store/ledger db) last :basis)))
      (is (nil? (:registry-number (store/application db "app-1"))) "nothing filed"))))

(deftest filing-with-an-incomplete-kyc-verdict-is-held
  (testing "an officer screened but only to :incomplete (missing id-doc) is not the same as :clear -> HOLD"
    (let [[db actor] (fresh)]
      ;; o-3 has no :id-doc and no sanctions hit in the demo data, so
      ;; screening it yields :incomplete (not :hit, not :clear).
      (store/commit-record! db {:effect :application/upsert
                                :value {:id "app-1" :officers ["o-3"]}})
      (exec-op actor "t5c-a" {:op :jurisdiction/assess :subject "app-1"} operator)
      (approve! actor "t5c-a")
      (exec-op actor "t5c-b" {:op :kyc/screen :subject "o-3"} operator)
      (approve! actor "t5c-b")
      (is (= :incomplete (:verdict (store/kyc-of db "o-3"))) "sanity: screening yielded :incomplete, not :clear")
      (let [res (exec-op actor "t5c" {:op :filing/submit :subject "app-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:kyc-incomplete} (-> (store/ledger db) last :basis)))))))

(deftest filing-submit-always-escalates-then-human-decides
  (testing "a clean, fully-assessed filing still ALWAYS interrupts for human approval -- actuation is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "app-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t6a" :resume? true})
          _ (exec-op actor "t6b" {:op :kyc/screen :subject "o-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t6b" :resume? true})
          r1 (exec-op actor "t6" {:op :filing/submit :subject "app-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, registry record drafted"
        (let [r2 (g/run* actor {:approval {:status :approved :by "op-1"}}
                         {:thread-id "t6" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :filed (:status (store/application db "app-1"))))
          (is (= 1 (count (store/registry-history db))) "one draft registry record")))))
  (testing "reject -> hold, nothing filed"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "app-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t7a" :resume? true})
          _  (exec-op actor "t7" {:op :filing/submit :subject "app-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/registry-history db)) "nothing drafted on reject"))))

(defn- file-app-1!
  "Drive app-1 all the way to :filed (assess -> approve, screen -> approve,
  file -> approve). Shared setup for the amendment tests below."
  [actor]
  (exec-op actor "setup-a" {:op :jurisdiction/assess :subject "app-1"} operator)
  (approve! actor "setup-a")
  (exec-op actor "setup-b" {:op :kyc/screen :subject "o-1"} operator)
  (approve! actor "setup-b")
  (exec-op actor "setup-c" {:op :filing/submit :subject "app-1"} operator)
  (approve! actor "setup-c"))

(deftest post-filing-intake-cannot-smuggle-changes-around-amendment
  (testing ":application/intake auto-commits with NO approval -- once :filed, it must not be able to
            silently rewrite capital, address or officers behind the actuation gate's back"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [before (store/application db "app-1")
            res (exec-op actor "t7a" {:op :application/intake :subject "app-1"
                                      :patch {:id "app-1" :capital 1 :address "FAKE"
                                             :officers ["o-1" "o-2"]}} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt, no auto-commit")
        (is (not= :interrupted (:status res)))
        (is (some #{:post-filing-intake-blocked} (-> (store/ledger db) last :basis)))
        (is (= before (store/application db "app-1")) "application completely unchanged")))))

(deftest post-filing-intake-is-blocked-even-for-a-harmless-looking-patch
  (testing "the block is unconditional once :filed -- not contingent on the patch looking dangerous"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [res (exec-op actor "t7b" {:op :application/intake :subject "app-1"
                                      :patch {:id "app-1" :entity-name "Kotoba Trading GK"}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:post-filing-intake-blocked} (-> (store/ledger db) last :basis)))))))

(deftest post-filing-intake-is-blocked-after-dissolution-too
  (testing "a dissolved application is just as protected as a filed one"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (exec-op actor "t7c-a" {:op :registry/dissolve :subject "app-1"
                              :reason "voluntary wind-up" :effective-date "2026-08-01"} operator)
      (approve! actor "t7c-a")
      (let [res (exec-op actor "t7c" {:op :application/intake :subject "app-1"
                                      :patch {:id "app-1" :status :intake}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:post-filing-intake-blocked} (-> (store/ledger db) last :basis)))
        (is (= :dissolved (:status (store/application db "app-1"))) "status was not reverted")))))

(deftest intake-before-filing-still-works-normally
  (testing "the block is post-filing ONLY -- pre-filing intake is unaffected and still auto-commits"
    (let [[db actor] (fresh)
          res (exec-op actor "t7d" {:op :application/intake :subject "app-1"
                                    :patch {:id "app-1" :capital 2000000}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 2000000 (:capital (store/application db "app-1")))))))

(deftest intake-cannot-fabricate-a-filed-status-with-a-fake-registry-number-and-lei
  (testing "the ONE op that auto-commits with ZERO human approval at any phase must not be
            able to mint a completely fake 'filed' company out of thin air -- no assessment,
            no KYC, no document check, no spec-basis, no human, ever (ADR Addendum 15: verified
            by direct exploitation that before this fix this committed instantly at phase 3)"
    (let [[db actor] (fresh)
          before (store/application db "app-1")
          res (exec-op actor "fab" {:op :application/intake :subject "app-1"
                                    :patch {:id "app-1" :status :filed
                                           :registry-number "JPN-99999999"
                                           :lei "FAKE0000000000000099"}} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt, no auto-commit")
      (is (not= :interrupted (:status res)))
      (is (some #{:intake-forbidden-field} (-> (store/ledger db) last :basis)))
      (is (some #{:intake-forbidden-status} (-> (store/ledger db) last :basis)))
      (is (= before (store/application db "app-1")) "application completely unchanged")
      (is (empty? (store/registry-history db)) "no registry record fabricated"))))

(deftest intake-cannot-target-a-different-application-than-its-declared-subject
  (testing "a request declaring subject app-2 (never filed, so post-filing-intake-violations
            passes it) whose patch's OWN :id names a DIFFERENT, already-filed application must
            not silently rewrite that other application -- formation.store's :application/upsert
            keys off patch's :id, not the request's declared subject (ADR Addendum 15: verified
            by direct exploitation this rewrote an unrelated already-filed app's capital/address)"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [before (store/application db "app-1")
            res (exec-op actor "confuse" {:op :application/intake :subject "app-2"
                                          :patch {:id "app-1" :capital 1
                                                 :address "REWRITTEN VIA SUBJECT CONFUSION"}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:intake-subject-mismatch} (-> (store/ledger db) last :basis)))
        (is (= before (store/application db "app-1")) "app-1 completely unchanged")))))

(deftest intake-setting-status-to-ready-is-still-unaffected
  (testing "the new intake-fabrication check only rejects :filed/:dissolved -- ordinary
            pre-filing status transitions (e.g. :ready) still auto-commit exactly as before"
    (let [[db actor] (fresh)
          res (exec-op actor "still-fine" {:op :application/intake :subject "app-1"
                                           :patch {:id "app-1" :status :ready}} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= :ready (:status (store/application db "app-1")))))))

(deftest amendment-without-a-filed-application-is-held
  (testing "an application with no registry_number has nothing to amend -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :registry/amend :subject "app-1"
                                   :changed-fields {:address "新住所"}
                                   :effective-date "2026-07-03"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-registry-number} (-> (store/ledger db) first :basis)))
      (is (empty? (store/registry-history db)) "no amendment drafted"))))

(defn- drift-jurisdiction-to-atl!
  "Simulates a data-consistency edge case: a filed application's
  :jurisdiction field drifts to one with no spec-basis AFTER filing.
  registry-number/status stay set from the original valid filing, so
  only the spec-basis check protects amend/dissolve here. Deliberately
  bypasses the actor (direct store/commit-record!, not :application/
  intake) -- post-filing-intake-violations now correctly blocks intake
  from touching a :filed application at all, so this can no longer
  happen through the actor's own operations; it models a lower-level
  data-migration bug or a future write-path this governor never saw."
  [db subject]
  (store/commit-record! db {:effect :application/upsert
                            :value {:id subject :jurisdiction "ATL"}}))

(deftest amendment-requires-a-spec-basis-even-with-a-registry-number
  (testing "a registry_number alone is not enough -- jurisdiction must still have a citable spec-basis"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (drift-jurisdiction-to-atl! db "app-1")
      (let [res (exec-op actor "t8c" {:op :registry/amend :subject "app-1"
                                      :changed-fields {:address "新住所"}
                                      :effective-date "2026-07-03"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:no-spec-basis} (-> (store/ledger db) last :basis)))))))

(deftest amendment-adding-an-officer-with-a-stored-sanctions-hit-is-held
  (testing "an amendment introducing an officer with a stored :hit KYC verdict gets the same scrutiny a filing would -- HOLD, un-overridable"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      ;; A :hit verdict can never be produced through the actor's own
      ;; :kyc/screen flow (a :hit proposal is itself a hard violation that
      ;; holds before it ever commits -- see sanctions-hit-is-held-and-
      ;; unoverridable). This models the real-world path that DOES leave a
      ;; :hit on file: an external re-screening / watchlist update after
      ;; the officer was originally cleared.
      (store/commit-record! db {:effect :kyc/set :path ["o-4"]
                                :payload {:officer-id "o-4" :verdict :hit}})
      (let [res (exec-op actor "t8d" {:op :registry/amend :subject "app-1"
                                      :changed-fields {:officers ["o-1" "o-4"]}
                                      :effective-date "2026-07-03"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:sanctions-hit} (-> (store/ledger db) last :basis)))
        (is (= ["o-1"] (:officers (store/application db "app-1"))) "officer roster unchanged")))))

(deftest amendment-adding-an-unscreened-officer-is-held
  (testing "an amendment introducing a never-screened officer -> HOLD (kyc-incomplete), same as a filing would require"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      ;; o-3 has never been screened at all in this test's db.
      (let [res (exec-op actor "t8e" {:op :registry/amend :subject "app-1"
                                      :changed-fields {:officers ["o-1" "o-3"]}
                                      :effective-date "2026-07-03"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:kyc-incomplete} (-> (store/ledger db) last :basis)))))))

(deftest amendment-not-touching-officers-is-unaffected-by-other-officers-status
  (testing "an address-only amendment does not need officer screening at all -- it introduces no new officer exposure"
    (let [[_db actor] (fresh)]
      (file-app-1! actor)
      (let [r1 (exec-op actor "t8f" {:op :registry/amend :subject "app-1"
                                     :changed-fields {:address "新住所"}
                                     :effective-date "2026-07-03"} operator)]
        (is (= :interrupted (:status r1)) "still escalates on actuation, but not held for officer reasons")
        (let [r2 (approve! actor "t8f")]
          (is (= :commit (get-in r2 [:state :disposition]))))))))

(deftest amendment-cannot-smuggle-a-status-change-through-changed-fields
  (testing "an amendment that ALSO flips :status :dissolved alongside an innocuous-looking
            address change is a HARD violation -- :status is lifecycle-managed exclusively
            by :filing/submit / :registry/dissolve, each with their own scrutiny that a
            :registry/amend proposal never runs (see ADR Addendum 14: verified by direct
            exploitation that, before this fix, this committed as a plain change-draft
            record with the application left in a bogus :dissolved state and no
            dissolution-draft ever appended)"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [res (exec-op actor "t8g" {:op :registry/amend :subject "app-1"
                                      :changed-fields {:address "新住所" :status :dissolved}
                                      :effective-date "2026-07-03"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:amendment-forbidden-field} (-> (store/ledger db) last :basis)))
        (is (= :filed (:status (store/application db "app-1"))) "status untouched, still :filed")
        (is (= 1 (count (store/registry-history db))) "no change-draft appended")))))

(deftest amendment-cannot-smuggle-a-jurisdiction-or-registry-number-change
  (testing "jurisdiction/registry-number/lei/id are registry-assigned or identity fields --
            never customer-editable via a mere amendment, regardless of spec-basis citation"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (doseq [forbidden-patch [{:jurisdiction "ATL"} {:registry-number "FAKE-999"}
                               {:lei "FAKE0000000000000000"} {:id "app-99"}]]
        (let [res (exec-op actor (str "t8h-" (name (first (keys forbidden-patch))))
                           {:op :registry/amend :subject "app-1"
                            :changed-fields forbidden-patch
                            :effective-date "2026-07-03"} operator)]
          (is (= :hold (get-in res [:state :disposition])) (str forbidden-patch " must be rejected"))
          (is (some #{:amendment-forbidden-field} (-> (store/ledger db) last :basis))))))))

(deftest amendment-with-no-changes-is-held
  (testing "an empty changed-fields amendment -> HOLD, even for a filed application"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [res (exec-op actor "t9" {:op :registry/amend :subject "app-1"
                                     :changed-fields {} :effective-date "2026-07-03"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:empty-amendment} (-> (store/ledger db) last :basis)))
        (is (= 1 (count (store/registry-history db))) "only the original incorporation record")))))

(deftest amendment-always-escalates-then-human-decides
  (testing "a clean amendment on a filed application still ALWAYS interrupts -- actuation is never auto"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [r1 (exec-op actor "t10" {:op :registry/amend :subject "app-1"
                                     :changed-fields {:address "新住所"}
                                     :effective-date "2026-07-03"} operator)]
        (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= "新住所" (:address (store/application db "app-1"))) "application record updated")
          (is (= 2 (count (store/registry-history db)))
              "original incorporation record + one appended amendment")
          (is (= "change-draft" (get (last (store/registry-history db)) "kind")))))))
  (testing "reject -> hold, application and registry-history unchanged"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (exec-op actor "t11" {:op :registry/amend :subject "app-1"
                            :changed-fields {:address "新住所"}
                            :effective-date "2026-07-03"} operator)
      (let [before-history (store/registry-history db)
            r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                       {:thread-id "t11" :resume? true})]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (not= "新住所" (:address (store/application db "app-1"))))
        (is (= before-history (store/registry-history db)) "nothing appended on reject")))))

(deftest dissolution-requires-a-spec-basis-even-with-a-registry-number
  (testing "a registry_number alone is not enough -- jurisdiction must still have a citable spec-basis"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (drift-jurisdiction-to-atl! db "app-1")
      (let [res (exec-op actor "t12c" {:op :registry/dissolve :subject "app-1"
                                       :reason "voluntary wind-up"
                                       :effective-date "2026-08-01"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:no-spec-basis} (-> (store/ledger db) last :basis)))))))

(deftest dissolution-without-a-filed-application-is-held
  (testing "an application with no registry_number has nothing to dissolve -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :registry/dissolve :subject "app-1"
                                    :reason "voluntary wind-up"
                                    :effective-date "2026-08-01"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-registry-number} (-> (store/ledger db) first :basis)))
      (is (empty? (store/registry-history db)) "no dissolution drafted"))))

(deftest dissolution-always-escalates-then-human-decides
  (testing "a clean dissolution on a filed application still ALWAYS interrupts -- actuation is never auto"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (let [r1 (exec-op actor "t13" {:op :registry/dissolve :subject "app-1"
                                     :reason "voluntary wind-up"
                                     :effective-date "2026-08-01"} operator)]
        (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :dissolved (:status (store/application db "app-1"))) "application marked dissolved")
          (is (= 2 (count (store/registry-history db)))
              "original incorporation record + one appended dissolution")
          (is (= "dissolution-draft" (get (last (store/registry-history db)) "kind")))))))
  (testing "reject -> hold, application not dissolved"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (exec-op actor "t14" {:op :registry/dissolve :subject "app-1"
                            :reason "voluntary wind-up"
                            :effective-date "2026-08-01"} operator)
      (let [r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                       {:thread-id "t14" :resume? true})]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (not= :dissolved (:status (store/application db "app-1"))))))))

(deftest double-dissolution-is-held
  (testing "an already-dissolved application cannot be dissolved again -> HOLD, un-overridable"
    (let [[db actor] (fresh)]
      (file-app-1! actor)
      (exec-op actor "t15a" {:op :registry/dissolve :subject "app-1"
                             :reason "voluntary wind-up"
                             :effective-date "2026-08-01"} operator)
      (approve! actor "t15a")
      (let [history-after-first (store/registry-history db)
            res (exec-op actor "t15b" {:op :registry/dissolve :subject "app-1"
                                       :reason "second attempt"
                                       :effective-date "2026-09-01"} operator)]
        (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
        (is (not= :interrupted (:status res)))
        (is (some #{:already-dissolved} (-> (store/ledger db) last :basis)))
        (is (= history-after-first (store/registry-history db))
            "no second dissolution record appended")))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :application/intake :subject "app-1"
                       :patch {:id "app-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

(deftest auto-committed-ledger-fact-has-no-fabricated-approver
  (testing "formation.store's own docstring promises the ledger can answer 'approved by whom' -- an
            AUTO-commit (phase 3's :application/intake, no human ever in the loop) must not invent one"
    (let [[db actor] (fresh)]
      (exec-op actor "t16" {:op :application/intake :subject "app-1"
                            :patch {:id "app-1" :status :ready}} operator)
      (is (nil? (:approved-by (last (store/ledger db))))))))

(deftest committed-ledger-fact-records-the-actual-approver
  (testing "a human-approved filing's ledger fact records WHO approved it, not just that someone did --
            previously the approver's identity was computed in :request-approval and then silently
            dropped (merged into an unread :payload key), so the ledger could never actually answer
            this despite formation.store's docstring claiming it could"
    (let [[db actor] (fresh)]
      (exec-op actor "t17a" {:op :jurisdiction/assess :subject "app-1"} operator)
      (approve! actor "t17a")
      (exec-op actor "t17b" {:op :kyc/screen :subject "o-1"} operator)
      (approve! actor "t17b")
      (exec-op actor "t17" {:op :filing/submit :subject "app-1"} operator)
      (let [r2 (g/run* actor {:approval {:status :approved :by "supervisor-9"}}
                       {:thread-id "t17" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= "supervisor-9" (:approved-by (last (store/ledger db))))
            "the approver, not the original requester's actor-id")))))

;; ----------------------------- cross-backend parity -----------------------------
;; The full actor flow -- not just raw store CRUD -- proven identical on
;; DatomicStore. These specifically re-run the highest-stakes invariants
;; from above (the critical post-filing-intake bypass fix, and the full
;; incorporate -> amend -> dissolve lifecycle) against `datomic-seed-db`.

(deftest post-filing-intake-blocked-on-datomic-store-too
  (testing "the critical actuation-gate-bypass fix holds on DatomicStore, not just MemStore"
    (let [[db actor] (fresh store/datomic-seed-db)]
      (file-app-1! actor)
      (let [before (store/application db "app-1")
            res (exec-op actor "d1" {:op :application/intake :subject "app-1"
                                     :patch {:id "app-1" :capital 1 :address "FAKE"
                                            :officers ["o-1" "o-2"]}} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:post-filing-intake-blocked} (-> (store/ledger db) last :basis)))
        (is (= before (store/application db "app-1")) "application completely unchanged")))))

(deftest full-lifecycle-on-datomic-store-too
  (testing "incorporate -> amend -> dissolve, and the double-dissolution hold, all behave identically on DatomicStore"
    (let [[db actor] (fresh store/datomic-seed-db)]
      (file-app-1! actor)
      (is (= :filed (:status (store/application db "app-1"))))
      (is (= 1 (count (store/registry-history db))))

      (exec-op actor "d2" {:op :registry/amend :subject "app-1"
                           :changed-fields {:address "新住所"}
                           :effective-date "2026-07-03"} operator)
      (approve! actor "d2")
      (is (= "新住所" (:address (store/application db "app-1"))))
      (is (= 2 (count (store/registry-history db))))

      (exec-op actor "d3" {:op :registry/dissolve :subject "app-1"
                           :reason "voluntary wind-up" :effective-date "2026-08-01"} operator)
      (approve! actor "d3")
      (is (= :dissolved (:status (store/application db "app-1"))))
      (is (= 3 (count (store/registry-history db))))

      (let [res (exec-op actor "d4" {:op :registry/dissolve :subject "app-1"
                                     :reason "second attempt" :effective-date "2026-09-01"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:already-dissolved} (-> (store/ledger db) last :basis)))
        (is (= 3 (count (store/registry-history db))) "no second dissolution appended")))))
