# cloud-itonami-M6910

[![ci](https://github.com/cloud-itonami/cloud-itonami-M6910/actions/workflows/ci.yml/badge.svg)](https://github.com/cloud-itonami/cloud-itonami-M6910/actions/workflows/ci.yml)

Open Business Blueprint for **ISIC Rev.5 6910**: legal activities, focused
on company incorporation / registration-agent services. This repository
publishes a global company-formation actor as an OSS business that any
qualified, licensed operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`robotaxi-actor`](https://github.com/com-junkawasaki/robotaxi-actor)
(AR1 ⊣ SafetyGovernor), [`cloud-itonami-6310`](https://github.com/cloud-itonami/cloud-itonami-6310)
(HR-LLM ⊣ PolicyGovernor, formerly `gftd-talent-actor`) and `ai-gftd-itonami`
(ops-LLM ⊣ CertGovernor). Here it is **Registrar-LLM ⊣ RegistrarGovernor**.

> **Why an actor layer at all?** An LLM is great at drafting a document
> checklist, normalizing intake and flagging a thin KYC file -- but it has
> **no notion of which sources are official, no legal standing, and no
> business being the one that decides a real filing goes to a real
> government today**. Letting it submit or pay directly invites fabricated
> legal requirements, laundering sanctioned parties into a filing, and
> silent liability for whoever runs it. This project seals the
> Registrar-LLM into a single node and wraps it with an independent
> **RegistrarGovernor**, a human **approval workflow**, and an immutable
> **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs a company-incorporation workflow. It does
**not**, by itself, hold a business license to practice as a registered
agent in any jurisdiction, and it does not claim to. Whoever deploys and
operates a live instance (a licensed formation agent, a law firm's ops
team, a corporate-services provider) supplies the jurisdiction-specific
license, the real KYC/AML program and the real government-portal /
payment integrations, and bears that jurisdiction's liability -- the
software supplies the governed, spec-cited, audited execution scaffold so
that operator does not have to build the compliance layer from scratch
for every new market. This mirrors why `cloud-itonami-6310` is "an OSS
replacement for a HR SaaS you run yourself": nobody centralizes the
liability, many operators can each run their own governed instance.

### Actuation

**A real government submission, a real amendment filing, a real
dissolution filing, or a real fee payment is never autonomous, at any
phase, by construction.** Two independent layers enforce this
(`formation.governor`'s `:actuation` high-stakes gate and
`formation.phase`'s phase table, which never puts `:filing/submit`,
`:registry/amend` or `:registry/dissolve` in any phase's `:auto` set) --
see `formation.phase`'s docstring and `test/formation/phase_test.clj`'s
`filing-submit-never-auto-at-any-phase`. The actor may draft, check,
screen and recommend; a human operator is always the one who actually
files, amends, dissolves and pays.

**`:application/intake` is the one op that DOES auto-commit** (it's the
only member of any phase's `:auto` set -- pre-filing customer data entry
needs to be fast, not gated). To keep that from becoming a backdoor
around everything above, the governor blocks intake outright once an
application is `:filed` or `:dissolved` (`:post-filing-intake-blocked`):
every post-filing change to capital, address, officers or anything else
must go through `:registry/amend` / `:registry/dissolve` instead, which
carry the full spec-basis + officer-screening + human-approval gate.

## The core contract

```
customer intake + jurisdiction facts (formation.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────┐
   │ Registrar-LLM │ ─────────────▶ │ RegistrarGovernor  │  (independent system)
   │   (sealed)    │  + citations   │ spec-basis · KYC   │
   └──────────────┘                 └─────────┬──────────┘
                             commit ◀──────────┼──────────▶ hold (fabricated law;
                                 │                  │         sanctions hit;
                           record + ledger    escalate ─▶ 人間承認    incomplete docs;
                                                (ALWAYS for :filing/submit)  un-overridable)
```

**The Registrar-LLM never files or pays a record the RegistrarGovernor
would reject, and never files without a human sign-off.** Hard violations
(fabricated jurisdiction requirements / sanctions hit / incomplete
documents) force **hold** and *cannot* be approved past; a clean filing
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean application through incorporation -> amendment -> dissolution, plus three HARD-hold cases
clojure -M:dev:test    # governor contract · phase invariants · LEI registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, RegistrarGovernor, LEI/registry draft record, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud, and [`docs/DESIGN.md`](docs/DESIGN.md) /
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Layout

| File | Role |
|---|---|
| `src/formation/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`, swappable to Datomic Local or a kotoba-server pod) + append-only audit ledger + draft registry history |
| `src/formation/registry.cljc` | ISO 17442 LEI issuance (ISO 7064 MOD 97-10) + incorporation/amendment/dissolution draft records -- ported from `matsurigoto`'s corp-registry (etzhayyim/root, ADR-2606062300) |
| `src/formation/facts.cljc` | Per-jurisdiction requirement catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/formation/registrarllm.cljc` | **Registrar-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/KYC/filing/amendment/dissolution proposals |
| `src/formation/governor.cljc` | **RegistrarGovernor** -- spec-basis · sanctions hold · KYC-complete · document-complete · post-filing-intake-block · amendment-target · dissolution-target · confidence floor · actuation gate |
| `src/formation/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (filing always human) |
| `src/formation/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/formation/sim.cljc` | demo driver |
| `test/formation/*_test.clj` | governor contract · phase invariants · LEI conformance · facts coverage · MemStore ≡ DatomicStore parity · real-LLM advisor (mock-model) |

## Jurisdiction coverage (honest)

`formation.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `formation.facts/catalog` --
currently 31 seeded (JPN, USA-DE, GBR, DEU, EST, KOR, IND, SGP, NZL, CAN,
FRA, NOR, DNK, FIN, BEL, CZE, AUS, ZAF, CHE, NLD, ISR, IRL, HKG, PRT, ESP,
ITA, SWE, POL, MEX, BRA) out of ~194 jurisdictions worldwide. This is a
starting catalog to prove the governor contract end-to-end, not a claim of
global coverage. Adding a jurisdiction is additive: one map entry in
`formation.facts/catalog`, citing a real official source -- never
fabricate a jurisdiction's requirements to make coverage look bigger.

## License

Code and implementation templates are AGPL-3.0-or-later.
