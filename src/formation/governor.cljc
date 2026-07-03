(ns formation.governor
  "RegistrarGovernor -- the independent compliance layer that earns the
  Registrar-LLM the right to commit. The LLM has no notion of jurisdiction
  law, sanctions exposure or when an act stops being a draft and becomes a
  real-world filing, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the company-formation analog of
  robotaxi's Minimal Risk Condition and gftd-talent-actor's PolicyGovernor.

  Eight checks, in priority order. The first six are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past a sanctions hit or a fabricated legal requirement). The last two are
  SOFT: they ask a human to look (low confidence / actuation), and the
  human may approve -- but see `formation.phase`: for `:stake :actuation`
  (a real government submission, amendment filing, dissolution filing, or
  fee payment) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis        -- did the jurisdiction proposal cite an OFFICIAL
                             source (`formation.facts`), or invent one?
    2. Sanctions hold     -- does any officer AT STAKE in this proposal
                             (a filing's full roster, or an amendment's
                             newly-introduced officers) carry a
                             sanctions/PEP hit (screened or on file)?
    3. KYC complete       -- has EVERY officer at stake actually been
                             screened and cleared? A never-screened
                             officer is not a hit (nil != :hit), so
                             `sanctions-hit` alone would let a filing (or
                             an amendment adding a new director) through
                             with zero screening ever performed.
    4. Document complete  -- for a filing proposal, are the jurisdiction's
                             required docs actually satisfied?
    5. Amendment target   -- for an amendment proposal, is there an
                             existing registry number to amend, and is the
                             amendment actually non-empty?
    6. Dissolution target -- for a dissolution proposal, is there an
                             existing registry number to dissolve, and is
                             the entity not ALREADY dissolved (no double
                             dissolution)?
    7. Confidence floor   -- LLM confidence below threshold -> escalate.
    8. Actuation gate     -- :stake :actuation -> always escalate; never
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
  "A `:jurisdiction/assess`, `:filing/submit`, `:registry/amend` or
  `:registry/dissolve` proposal with no spec-basis citation is a HARD
  violation -- never invent a country's law. Amendment and dissolution
  are not exempt from this just because a registry_number already
  exists: 'there is a record to change' is not the same as 'we know how
  this jurisdiction wants it changed'."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :filing/submit :registry/amend :registry/dissolve} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- officers-at-stake
  "Which officer-ids does THIS proposal actually put in front of the
  registry? `:filing/submit` puts every officer currently on the
  application. `:registry/amend` puts only the officers an amendment
  actually INTRODUCES via `changed-fields :officers` -- an amendment that
  does not touch :officers introduces no new officer exposure and must
  not be blocked by an unrelated officer's status. This is the single
  place both `sanctions-violations` and `kyc-completeness-violations`
  consult, so 'which ops screen officers' cannot drift between the two
  checks."
  [{:keys [op subject]} proposal st]
  (case op
    :filing/submit  (:officers (store/application st subject))
    :registry/amend (get-in proposal [:value :changed-fields :officers])
    nil))

(defn- sanctions-violations
  "A sanctions/PEP hit on any officer involved -- screened in THIS proposal
  or already on file in the store -- is a HARD, un-overridable hold. Covers
  both a fresh filing's officer roster and any officer an AMENDMENT
  introduces (adding a sanctioned director via a 'mere' address-change
  amendment must not bypass the same scrutiny a filing would get)."
  [request proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        officer-ids (officers-at-stake request proposal st)
        hit-on-file? (some #(= :hit (:verdict (store/kyc-of st %))) officer-ids)]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のある関係者を含む申請は進められない"}])))

(defn- kyc-completeness-violations
  "For `:filing/submit`, EVERY officer on the application must have been
  KYC-screened AND cleared (`:verdict :clear`) -- HARD, un-overridable.
  `sanctions-violations` alone is not enough: an officer who was simply
  never screened has a nil verdict, and nil is not :hit, so a filing with
  zero screening ever performed would otherwise sail through clean. The
  same rule applies to any officer a `:registry/amend` introduces -- a
  newly-added director must be cleared before the amendment can proceed,
  not just checked for a sanctions hit."
  [request proposal st]
  (let [officer-ids (officers-at-stake request proposal st)
        cleared? (fn [oid] (= :clear (:verdict (store/kyc-of st oid))))]
    (when (and (seq officer-ids) (not (every? cleared? officer-ids)))
      [{:rule :kyc-incomplete
        :detail "全officerのKYCスクリーニング(:clear)が完了していない状態での提出提案"}])))

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

(defn- dissolution-violations
  "For `:registry/dissolve`, the target must already carry a registry
  number (you cannot dissolve a filing that was never submitted), and it
  must not already be dissolved -- both HARD."
  [{:keys [op subject]} st]
  (when (= op :registry/dissolve)
    (let [app (store/application st subject)]
      (cond-> []
        (nil? (:registry-number app))
        (conj {:rule :no-registry-number
               :detail "初回登記(registry_number)が無い申請には解散登記できない"})
        (= :dissolved (:status app))
        (conj {:rule :already-dissolved
               :detail "既に解散済みの申請への二重解散提案"})))))

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
                           (kyc-completeness-violations request proposal st)
                           (document-violations request st)
                           (amendment-violations request proposal st)
                           (dissolution-violations request st)))
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
