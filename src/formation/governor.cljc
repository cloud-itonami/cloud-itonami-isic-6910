(ns formation.governor
  "RegistrarGovernor -- the independent compliance layer that earns the
  Registrar-LLM the right to commit. The LLM has no notion of jurisdiction
  law, sanctions exposure or when an act stops being a draft and becomes a
  real-world filing, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the company-formation analog of
  robotaxi's Minimal Risk Condition and gftd-talent-actor's PolicyGovernor.

  Six checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past a sanctions hit or a fabricated legal requirement). The last two are
  SOFT: they ask a human to look (low confidence / actuation), and the
  human may approve -- but see `formation.phase`: for `:stake :actuation`
  (a real government submission, amendment filing, or fee payment) NO
  phase ever allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis        -- did the jurisdiction proposal cite an OFFICIAL
                             source (`formation.facts`), or invent one?
    2. Sanctions hold     -- does any officer on the application carry a
                             sanctions/PEP hit (screened or on file)?
    3. Document complete  -- for a filing proposal, are the jurisdiction's
                             required docs actually satisfied?
    4. Amendment target   -- for an amendment proposal, is there an
                             existing registry number to amend, and is the
                             amendment actually non-empty?
    5. Confidence floor   -- LLM confidence below threshold -> escalate.
    6. Actuation gate     -- :stake :actuation -> always escalate; never
                             auto, at any phase (structural, not a policy
                             toggle)."
  (:require [formation.facts :as facts]
            [formation.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = a real government filing submission or a real fee payment.
  There is exactly one member on purpose: actuation is not a spectrum."
  #{:actuation})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:filing/submit`) proposal with no
  spec-basis citation is a HARD violation -- never invent a country's law."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :filing/submit} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- sanctions-violations
  "A sanctions/PEP hit on any officer involved -- screened in THIS proposal
  or already on file in the store -- is a HARD, un-overridable hold."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        officer-ids (when (= op :filing/submit)
                      (:officers (store/application st subject)))
        hit-on-file? (some #(= :hit (:verdict (store/kyc-of st %))) officer-ids)]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のある関係者を含む申請は進められない"}])))

(defn- document-violations
  "For `:filing/submit`, the jurisdiction's required docs must actually be
  satisfied -- do not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :filing/submit)
    (let [app (store/application st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-docs-satisfied?
                      (:jurisdiction app) (:checklist assessment)))
        [{:rule :incomplete-documents
          :detail "法域の必要書類が充足していない状態での提出提案"}]))))

(defn- amendment-violations
  "For `:registry/amend`, the target application must already carry a
  registry number (you cannot amend a filing that was never submitted),
  and the amendment must actually change something -- both HARD."
  [{:keys [op subject]} proposal st]
  (when (= op :registry/amend)
    (let [app (store/application st subject)
          changed (get-in proposal [:value :changed-fields])]
      (cond-> []
        (nil? (:registry-number app))
        (conj {:rule :no-registry-number
               :detail "初回登記(registry_number)が無い申請には変更登記できない"})
        (empty? changed)
        (conj {:rule :empty-amendment
               :detail "変更内容が空の変更登記提案"})))))

(defn check
  "Censors a Registrar-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       -- at least one HARD violation. Forces HOLD; a human
                    cannot override.
   - :escalate?   -- soft: low confidence OR actuation. A human decides.
   - :ok?         -- clean AND not escalating: safe to auto-commit."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (sanctions-violations request proposal st)
                           (document-violations request st)
                           (amendment-violations request proposal st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
