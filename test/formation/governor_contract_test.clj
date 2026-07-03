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

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

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

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

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

(deftest amendment-without-a-filed-application-is-held
  (testing "an application with no registry_number has nothing to amend -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :registry/amend :subject "app-1"
                                   :changed-fields {:address "新住所"}
                                   :effective-date "2026-07-03"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-registry-number} (-> (store/ledger db) first :basis)))
      (is (empty? (store/registry-history db)) "no amendment drafted"))))

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

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :application/intake :subject "app-1"
                       :patch {:id "app-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
