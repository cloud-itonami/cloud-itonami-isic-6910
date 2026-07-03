(ns formation.phase
  "Phase 0->3 staged rollout -- the company-formation analog of robotaxi's
  ODD phases and gftd-talent-actor's rollout: start narrow (read-only),
  widen as trust grows. Where the RegistrarGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have
  *yet*?'. It can only ever make the actor MORE conservative than the
  governor, never the reverse.

    Phase 0  read-only        -- coverage/checklist reads only (still
                                 governor-gated). Shadow/observe.
    Phase 1  assisted-intake  -- application intake allowed, every write
                                 needs human approval.
    Phase 2  + assess/screen  -- adds jurisdiction assessment + KYC
                                 screening writes (still approval).
    Phase 3  supervised auto  -- governor-clean, high-confidence INTAKE
                                 writes may auto-commit. Assessment and KYC
                                 screening still escalate (a human should
                                 see a jurisdiction/officer determination
                                 before it becomes the basis for a filing).

  `:filing/submit` and `:registry/amend` (and any future `:payment/remit`)
  are deliberately ABSENT from every phase's `:auto` set, including phase
  3 -- this is a permanent structural fact about this table, not a
  rollout milestone still to come. A real government submission, a real
  amendment filing, or a real fee payment is always a human call; see
  README `Actuation`. The RegistrarGovernor's `:actuation` high-stakes
  gate (formation.governor) enforces the same invariant independently --
  two layers, not one, agree on this."
  )

(def read-ops  #{:coverage/report})
(def write-ops #{:application/intake :jurisdiction/assess :kyc/screen :filing/submit :registry/amend})

;; NOTE the invariant: :filing/submit and :registry/amend are members of
;; `write-ops` (they are governor-gated like any write) but are NEVER a
;; member of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"          :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake"    :writes #{:application/intake}                                :auto #{}}
   2 {:label "assisted-assess"    :writes #{:application/intake :jurisdiction/assess :kyc/screen} :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops                                              :auto #{:application/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:filing/submit` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a RegistrarGovernor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
