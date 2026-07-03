(ns formation.governor
  "RegistrarGovernor -- the independent compliance layer that earns the
  Registrar-LLM the right to commit. The LLM has no notion of jurisdiction
  law, sanctions exposure or when an act stops being a draft and becomes a
  real-world filing, so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD -- the company-formation analog of
  robotaxi's Minimal Risk Condition and gftd-talent-actor's PolicyGovernor.

  Eleven checks, in priority order. The first nine are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past a sanctions hit or a fabricated legal requirement). The last two are
  SOFT: they ask a human to look (low confidence / actuation), and the
  human may approve -- but see `formation.phase`: for `:stake :actuation`
  (a real government submission, amendment filing, dissolution filing, or
  fee payment) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Effect matches op -- does the proposal's :effect (what actually
                             gets written to the SSoT on commit) match the
                             ONE legitimate effect for the REQUEST's :op
                             (`op->effect`)? Every check below keys off
                             the request's :op, not the proposal's
                             self-reported :effect -- without this check
                             first, an advisor (an untrusted, possibly
                             hallucinating real LLM) could answer a
                             harmless-looking :jurisdiction/assess request
                             with `:effect :filing/mark-submitted`, and a
                             human approving what looks like an assessment
                             would silently trigger a REAL filing with
                             none of :filing/submit's own scrutiny
                             (spec-basis-for-filing, document-complete,
                             KYC-complete) ever run.
    2. Spec-basis        -- did the jurisdiction proposal cite an OFFICIAL
                             source (`formation.facts`), or invent one?
    3. Sanctions hold     -- does any officer AT STAKE in this proposal
                             (a filing's full roster, or an amendment's
                             newly-introduced officers) carry a
                             sanctions/PEP hit (screened or on file)?
    4. KYC complete       -- has EVERY officer at stake actually been
                             screened and cleared? A never-screened
                             officer is not a hit (nil != :hit), so
                             `sanctions-hit` alone would let a filing (or
                             an amendment adding a new director) through
                             with zero screening ever performed.
    5. Document complete  -- for a filing proposal, are the jurisdiction's
                             required docs actually satisfied?
    6. Post-filing intake -- `:application/intake` is the ONLY op any
                             phase ever puts in its `:auto` set (it
                             auto-commits with NO human approval). Once an
                             application is :filed or :dissolved, intake
                             is blocked outright -- every further change
                             (capital, address, officers, anything) MUST
                             go through :registry/amend or
                             :registry/dissolve, which cite a spec-basis,
                             screen officers and always require a human.
                             Without this, intake is an unguarded
                             backdoor around the entire actuation gate.
    7. Intake fabrication -- even a PRE-filing intake patch is not a blank
                             cheque: it may never set :registry-number/
                             :lei (those are assigned ONLY by a real
                             filing), never set :status to :filed/
                             :dissolved (those are terminal actuation
                             states reached ONLY via the internal
                             app-patch a real filing/dissolution applies
                             itself), and its own patch :id (if present)
                             must match the request's :subject (otherwise
                             a decoy, never-filed subject can pass every
                             check while the patch's real :id target gets
                             silently rewritten).
    8. Amendment target   -- for an amendment proposal, is there an
                             existing registry number to amend, is the
                             amendment actually non-empty, and does it stay
                             within `amendable-fields` (never :status/
                             :jurisdiction/:registry-number/:lei/:id)?
    9. Dissolution target -- for a dissolution proposal, is there an
                             existing registry number to dissolve, and is
                             the entity not ALREADY dissolved (no double
                             dissolution)?
   10. Confidence floor   -- LLM confidence below threshold -> escalate.
   11. Actuation gate     -- :stake :actuation -> always escalate; never
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

(def op->effect
  "The ONE legitimate `:effect` a proposal may declare for each op --
  `formation.operation/commit-record` takes `:effect` straight from the
  (untrusted) advisor proposal with no cross-check of its own, so this
  table is the only thing standing between 'the request says :jurisdiction/
  assess' and 'the SSoT mutation that actually runs is :filing/mark-
  submitted'. Every other check in this namespace keys off the REQUEST's
  :op -- so a mismatched :effect would let all of THEIR scrutiny run
  against the wrong (lower-stakes) op while a different, possibly far
  higher-stakes effect gets committed."
  {:application/intake  :application/upsert
   :jurisdiction/assess :assessment/set
   :kyc/screen          :kyc/set
   :filing/submit       :filing/mark-submitted
   :registry/amend      :registry/amend-submitted
   :registry/dissolve   :registry/dissolve-submitted})

(defn- effect-mismatch-violations
  "HARD, checked first: a proposal whose :effect is not the one paired
  with the request's :op in `op->effect` is rejected outright, before any
  op-specific check below even runs -- see `op->effect`'s docstring for
  why this must come first."
  [{:keys [op]} proposal]
  (when-let [expected (op->effect op)]
    (when (not= expected (:effect proposal))
      [{:rule :effect-mismatch
        :detail (str "op " op " の提案は :effect " expected
                     " のはずが実際には " (:effect proposal) " になっている")}])))

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

(defn- post-filing-intake-violations
  "`:application/intake` exists for PRE-FILING customer data entry --
  normalizing/validating a patch before anything is submitted to a real
  registry. It is also the ONLY op in ANY phase's `:auto` set
  (`formation.phase`), meaning it auto-commits with no human approval at
  all. Once an application is `:filed` or `:dissolved`, allowing intake to
  keep touching it would let capital, address, officers or even status
  itself be silently rewritten with ZERO governor scrutiny -- a
  structural bypass of the entire actuation gate that :registry/amend and
  :registry/dissolve exist to enforce. HARD, un-overridable: the fix is
  always 'use :registry/amend (or :registry/dissolve)', never 'approve
  the intake anyway'."
  [{:keys [op subject]} st]
  (when (= op :application/intake)
    (let [app (store/application st subject)]
      (when (contains? #{:filed :dissolved} (:status app))
        [{:rule :post-filing-intake-blocked
          :detail "登記/解散済みの申請への intake 経由の変更は禁止。:registry/amend または :registry/dissolve を使うこと"}]))))

(def amendable-fields
  "The ONLY application fields a `:registry/amend` may touch -- an
  ALLOWLIST, not a denylist, so a newly-added application field defaults
  to forbidden until someone deliberately decides it belongs here.
  Everything else is structurally off-limits to a mere amendment, most
  importantly `:status` -- lifecycle-managed exclusively by
  `:filing/submit` / `:registry/dissolve`, each with their own dedicated
  governor scrutiny (document-complete, dissolution-target/double-
  dissolution) that a `:registry/amend` proposal never runs. Also
  forbidden: `:jurisdiction`/`:registry-number`/`:lei` (registry-assigned,
  never customer-editable via a change filing) and `:id` (identity).
  `:officers` IS amendable -- it already carries its own KYC/sanctions
  scrutiny via `officers-at-stake`."
  #{:entity-name :address :capital :articles :officers})

(defn- intake-fabrication-violations
  "HARD: `:application/intake` is the ONE op any phase ever auto-commits
  (formation.phase) -- zero human approval, ever. Its patch content needs
  the same structural limits `:registry/amend` gets from
  `amendable-fields` (Addendum 14), but pre-filing intake has its OWN
  failure modes:

    - `:registry-number` / `:lei` are assigned ONLY by a real
      `:filing/submit` (`formation.store/file!`). An intake patch setting
      either fabricates a complete fake filing -- fake LEI, fake
      registry-number, ZERO registry-history entry, zero spec-basis, zero
      document check, zero KYC screening, zero human ever involved.
    - `:status :filed` / `:dissolved` are terminal actuation states
      reached ONLY via the internal app-patch that `:filing/mark-
      submitted` / `:registry/dissolve-submitted` apply themselves. An
      intake patch claiming either fabricates the actuation event itself,
      and also flips on `post-filing-intake-violations`' own protection --
      making the fabrication permanent and indistinguishable from a real
      filing to every later check.
    - patch's own `:id`, if present, must equal the request's `:subject`.
      `post-filing-intake-violations` (and every other check here) looks
      up the application via the REQUEST's `:subject`, but
      `formation.store`'s `:application/upsert` keys the actual write off
      `(:id patch)`, not the request path. A request declaring a decoy,
      never-filed `:subject` while `patch`'s `:id` names a DIFFERENT,
      already-filed application lets the decoy's clean status pass every
      check while the real target gets silently rewritten.

  All three verified by direct exploitation (ADR Addendum 15) before this
  check existed: a from-scratch application with no assessment, no KYC,
  and phase-3 auto-commit alone produced a fully `:filed` record with a
  fabricated registry-number and LEI in one `:application/intake` call,
  and a decoy-subject patch rewrote an unrelated already-filed
  application's capital and address."
  [{:keys [op subject]} proposal]
  (when (= op :application/intake)
    (let [patch (:value proposal)]
      (cond-> []
        (some #(contains? patch %) [:registry-number :lei])
        (conj {:rule :intake-forbidden-field
               :detail "intake で registry-number/lei を設定することはできない（実際の filing でのみ発行される）"})
        (contains? #{:filed :dissolved} (:status patch))
        (conj {:rule :intake-forbidden-status
               :detail "intake で :status を :filed/:dissolved にすることはできない（実際の filing/dissolve でのみ到達する終端状態）"})
        (and (contains? patch :id) (not= (:id patch) subject))
        (conj {:rule :intake-subject-mismatch
               :detail "patch の :id がリクエストの subject と一致しない"})))))

(defn- amendment-violations
  "For `:registry/amend`, the target application must already carry a
  registry number (you cannot amend a filing that was never submitted),
  the amendment must actually change something, and it must not touch any
  field outside `amendable-fields` -- all HARD. Without the last check, an
  amendment proposal could smuggle `{:status :dissolved}` (or `:jurisdiction`,
  `:registry-number`, `:lei`) into `changed-fields` alongside an innocuous-
  looking address change: it would commit as a plain 'change-draft' record
  with NONE of `:registry/dissolve`'s own scrutiny (spec-basis-for-
  dissolution, double-dissolution guard) ever run, and registry-history
  would show a misleading address-change record for what was actually a
  dissolution -- corrupting the very audit trail this actor exists to keep
  trustworthy (verified via direct exploitation: see ADR Addendum 14)."
  [{:keys [op subject]} proposal st]
  (when (= op :registry/amend)
    (let [app (store/application st subject)
          changed (get-in proposal [:value :changed-fields])
          forbidden (remove amendable-fields (keys changed))]
      (cond-> []
        (nil? (:registry-number app))
        (conj {:rule :no-registry-number
               :detail "初回登記(registry_number)が無い申請には変更登記できない"})
        (empty? changed)
        (conj {:rule :empty-amendment
               :detail "変更内容が空の変更登記提案"})
        (seq forbidden)
        (conj {:rule :amendment-forbidden-field
               :detail (str "変更登記で変更できないフィールドが含まれている（"
                            "status/jurisdiction/registry-number/lei/id は "
                            ":registry/amend の対象外）: " (vec forbidden))})))))

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
                   (concat (effect-mismatch-violations request proposal)
                           (spec-basis-violations request proposal)
                           (sanctions-violations request proposal st)
                           (kyc-completeness-violations request proposal st)
                           (document-violations request st)
                           (post-filing-intake-violations request st)
                           (intake-fabrication-violations request proposal)
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
