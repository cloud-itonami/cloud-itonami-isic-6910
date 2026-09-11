(ns formation.llm-advisor-test
  "The real-inference advisor (langchain.model ChatModel), driven offline by
  langchain's mock-model. Proves: a real LLM proposal is parsed, still
  fully censored by the RegistrarGovernor, and that an unparseable/garbage
  response -- or one that fabricates a jurisdiction's requirements, or one
  that answers a harmless-looking request with a mismatched, higher-stakes
  :effect -- can never auto-file or auto-pay."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [langchain.model :as model]
            [formation.registrarllm :as registrarllm]
            [formation.governor :as governor]
            [formation.store :as store]
            [formation.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :registrar :phase 3})
(def assess-req {:op :jurisdiction/assess :subject "app-1"})

(defn- advise-with [req content]
  (registrarllm/-advise (registrarllm/llm-advisor (model/mock-model [{:role :assistant :content content}]))
                        (store/seed-db) req))

(deftest clean-llm-assessment-is-parsed-and-accepted
  (let [p (advise-with assess-req
                       (str "{:summary \"JPN 向け必要書類を提案\" :rationale \"法務局の公式ソースに基づく\" "
                            ":cites [\"商業登記法\" \"https://www.moj.go.jp/MINJI/minji06.html\"] "
                            ":effect :assessment/set "
                            ":value {:jurisdiction \"JPN\" :checklist [] :spec-basis \"https://www.moj.go.jp/MINJI/minji06.html\"} "
                            ":stake nil :confidence 0.9}"))]
    (is (= :assessment/set (:effect p)))
    (is (seq (:cites p)))
    (is (= 0.9 (:confidence p)))
    (testing "the governor accepts a proposal that actually cites a spec-basis"
      (is (:ok? (governor/check assess-req operator p (store/seed-db)))))))

(deftest llm-fabricating-a-jurisdiction-is-rejected
  (testing "even a confident LLM can't invent a jurisdiction's requirements -- spec-basis gate holds"
    (let [p (advise-with assess-req
                         (str "{:summary \"ATL 向け必要書類を提案\" :rationale \"一般的な慣行に基づく推測\" "
                              ":cites [] :effect :assessment/set "
                              ":value {:jurisdiction \"ATL\" :checklist [\"some doc\"]} "
                              ":confidence 0.95}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:no-spec-basis} (map :rule (:violations v)))))))

(deftest llm-declaring-a-sanctions-hit-is-unoverridable
  (testing "an LLM-reported sanctions hit still forces HOLD, regardless of confidence"
    (let [p (advise-with {:op :kyc/screen :subject "o-1"}
                         (str "{:summary \"制裁リスト一致\" :rationale \"screening provider hit\" "
                              ":cites [:sanctions-list] :effect :kyc/set "
                              ":value {:officer-id \"o-1\" :verdict :hit} :confidence 0.98}"))
          v (governor/check {:op :kyc/screen :subject "o-1"} operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:sanctions-hit} (map :rule (:violations v)))))))

(deftest unparseable-llm-output-never-auto-commits
  (testing "garbage / refusal -> safe noop at confidence 0 -> governor won't pass it"
    (let [p (advise-with assess-req "申し訳ございませんが、その法域についてはお答えできません。")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p)))
      (is (not (:ok? (governor/check assess-req operator p (store/seed-db))))))))

(deftest llm-answering-an-assessment-request-with-a-filing-effect-is-rejected
  (testing "a harmless-looking :jurisdiction/assess request answered with :effect
            :filing/mark-submitted -- even with plausible cites and high confidence --
            is a HARD violation, not just a low-confidence escalation"
    (let [p (advise-with assess-req
                         (str "{:summary \"JPN 向け必要書類を提案\" :rationale \"法務局の公式ソースに基づく\" "
                              ":cites [\"商業登記法\" \"https://www.moj.go.jp/MINJI/minji06.html\"] "
                              ":effect :filing/mark-submitted "
                              ":value {:application-id \"app-1\"} "
                              ":stake nil :confidence 0.95}"))
          v (governor/check assess-req operator p (store/seed-db))]
      (is (:hard? v))
      (is (some #{:effect-mismatch} (map :rule (:violations v)))))))

(deftest effect-mismatch-cannot-actually-file-through-the-full-actor-graph
  (testing "end-to-end reproduction: a :jurisdiction/assess request whose LLM proposal
            declares :effect :filing/mark-submitted must HOLD outright (no interrupt, no
            approval step at all) and leave the application completely untouched -- not
            merely fail a unit check. Before this fix, an approver who thought they were
            approving a routine assessment would silently trigger a real filing with none
            of :filing/submit's own document/KYC scrutiny ever run."
    (let [db (store/seed-db)
          before (store/application db "app-1")
          advisor (registrarllm/llm-advisor
                   (model/mock-model
                    [{:role :assistant
                      :content (str "{:summary \"JPN 向け必要書類を提案\" :rationale \"法務局の公式ソースに基づく\" "
                                    ":cites [\"商業登記法\" \"https://www.moj.go.jp/MINJI/minji06.html\"] "
                                    ":effect :filing/mark-submitted "
                                    ":value {:application-id \"app-1\"} "
                                    ":stake nil :confidence 0.95}")}]))
          actor (op/build db {:advisor advisor})
          res (g/run* actor {:request assess-req :context operator} {:thread-id "exploit"})]
      (is (= :done (:status res)) "settles immediately -- never even reaches request-approval")
      (is (= :hold (get-in res [:state :disposition])))
      (is (= before (store/application db "app-1")) "application completely unchanged")
      (is (empty? (store/registry-history db)) "nothing filed")
      (is (nil? (store/assessment-of db "app-1")) "no assessment written either"))))
