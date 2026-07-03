(ns formation.registrarllm
  "Registrar-LLM client -- the *contained intelligence node*.

  It normalizes customer intake, drafts a per-jurisdiction document
  checklist + fee estimate, screens officers against a KYC/sanctions
  signal, drafts the filing-submission action, and drafts
  post-incorporation amendments (変更登記) and dissolutions (解散/清算).
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record or a
  real government submission. Every output is censored downstream by
  `formation.governor` before anything touches the SSoT, and
  `:filing/submit` / `:registry/amend` / `:registry/dissolve` /
  `:payment/remit` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like `talent.hrllm` / `itonami.opsllm`, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real filing/payment
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [formation.facts :as facts]
            [formation.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it does
  not invent officers, capital or jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "申請レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :application/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction document checklist + fee-estimate draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a checklist
  for a jurisdiction with NO official spec-basis in `formation.facts` --
  the RegistrarGovernor must reject this (never invent a country's law)."
  [db {:keys [subject no-spec?]}]
  (let [app (store/application db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction app))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "formation.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-docs sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-docs sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-kyc
  "KYC / sanctions screening draft. `:sanctions-hit?` on the officer record
  injects the failure mode: the RegistrarGovernor must HOLD, un-overridably,
  on any sanctions/PEP hit. Missing identification yields low confidence ->
  escalate rather than auto-clear."
  [db {:keys [subject]}]
  (let [off (store/officer db subject)]
    (cond
      (nil? off)
      {:summary "対象officerが見つかりません" :rationale "no officer record"
       :cites [] :effect :kyc/set :value {:officer-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:sanctions-hit? off)
      {:summary    (str (:name off) ": 制裁/PEPリストと一致")
       :rationale  "スクリーニングが一致を検出。人手確認とホールドが必須。"
       :cites      [:sanctions-list]
       :effect     :kyc/set
       :value      {:officer-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:id-doc off))
      {:summary    (str (:name off) ": 本人確認書類が未提出")
       :rationale  "本人確認書類が無いため確信度を上げられない。"
       :cites      [:id-doc]
       :effect     :kyc/set
       :value      {:officer-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      {:summary    (str (:name off) ": 制裁リスト一致なし、本人確認書類あり")
       :rationale  "本人確認書類確認 + 制裁リスト非一致。"
       :cites      [:id-doc :sanctions-list]
       :effect     :kyc/set
       :value      {:officer-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-filing
  "Draft the actual government-submission + fee-payment action. ALWAYS
  `:stake :actuation` -- this is a REAL-WORLD act (a government registry
  receives a filing; money moves), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`formation.phase`); the governor also always escalates on
  `:actuation`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [app (store/application db subject)
        assessment (store/assessment-of db subject)
        docs-ok? (and assessment (facts/required-docs-satisfied?
                                  (:jurisdiction app)
                                  (:checklist assessment)))]
    {:summary    (str (:entity-name app) " を " (:jurisdiction app)
                      " へ提出する準備ができました" (when-not docs-ok? " (書類未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :filing/mark-submitted
     :value      {:application-id subject}
     :stake      :actuation
     :confidence (if docs-ok? 0.9 0.3)}))

(defn- propose-amendment
  "Draft a post-incorporation amendment (変更登記, e.g. registered-address or
  officer change). ALWAYS `:stake :actuation` -- an amendment is submitted
  to the same real government registry as the original filing, so it is
  gated exactly like `:filing/submit`: never auto-committed at any phase
  (`formation.phase`), always escalated by the governor's actuation gate.
  An application that was never filed (no registry-number) has nothing to
  amend -- the governor's hard `:no-registry-number` check catches that."
  [db {:keys [subject changed-fields effective-date]}]
  (let [app (store/application db subject)]
    (if (:registry-number app)
      {:summary    (str (:entity-name app) " (" (:registry-number app)
                        ") の変更登記案: " (pr-str (keys changed-fields)))
       :rationale  (str "既存登記記録 " (:registry-number app) " への追記型修正。")
       :cites      [(:registry-number app)]
       :effect     :registry/amend-submitted
       :value      {:application-id subject :changed-fields changed-fields
                    :effective-date effective-date}
       :stake      :actuation
       :confidence 0.9}
      {:summary    (str (:entity-name app) " は未登記のため変更登記できません")
       :rationale  "registry_number が無い = 初回登記が未提出。"
       :cites      []
       :effect     :registry/amend-submitted
       :value      {:application-id subject :changed-fields changed-fields
                    :effective-date effective-date}
       :stake      :actuation
       :confidence 0.2})))

(defn- propose-dissolution
  "Draft a company dissolution (解散/清算). ALWAYS `:stake :actuation` --
  submitted to the same real government registry as the original filing;
  gated exactly like `:filing/submit` / `:registry/amend`. A target that
  was never filed has nothing to dissolve, and an already-dissolved
  target cannot be dissolved twice -- both are the governor's hard
  `:registry/dissolve`-specific checks."
  [db {:keys [subject reason effective-date]}]
  (let [app (store/application db subject)]
    (cond
      (nil? (:registry-number app))
      {:summary "未登記のため解散できません" :rationale "registry_number が無い"
       :cites [] :effect :registry/dissolve-submitted
       :value {:application-id subject :reason reason :effective-date effective-date}
       :stake :actuation :confidence 0.2}

      (= :dissolved (:status app))
      {:summary (str (:entity-name app) " は既に解散済みです") :rationale "二重解散の防止"
       :cites [] :effect :registry/dissolve-submitted
       :value {:application-id subject :reason reason :effective-date effective-date}
       :stake :actuation :confidence 0.2}

      :else
      {:summary    (str (:entity-name app) " (" (:registry-number app) ") の解散案: " reason)
       :rationale  (str "既存登記記録 " (:registry-number app) " への追記型解散記録。")
       :cites      [(:registry-number app)]
       :effect     :registry/dissolve-submitted
       :value      {:application-id subject :reason reason :effective-date effective-date}
       :stake      :actuation
       :confidence 0.9})))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :application/intake   (normalize-intake db request)
    :jurisdiction/assess  (assess-jurisdiction db request)
    :kyc/screen           (screen-kyc db request)
    :filing/submit        (propose-filing db request)
    :registry/amend       (propose-amendment db request)
    :registry/dissolve    (propose-dissolution db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは法人設立(会社登記)エージェントの助言者です。与えられた事実のみに"
       "基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:application/upsert|:assessment/set|:kyc/set|:filing/mark-submitted|:registry/amend-submitted|:registry/dissolve-submitted) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:application (store/application st subject)}
    :kyc/screen          {:officer (store/officer st subject)}
    :filing/submit       {:application (store/application st subject)
                          :assessment (store/assessment-of st subject)}
    :registry/amend      {:application (store/application st subject)}
    :registry/dissolve   {:application (store/application st subject)}
    {:application (store/application st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the RegistrarGovernor escalates/holds -- an
  LLM hiccup can never auto-file or auto-pay."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :registrarllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
