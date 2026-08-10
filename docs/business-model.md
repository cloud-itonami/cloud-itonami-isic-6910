# Open Business Blueprint: cloud-itonami-isic-6910

This repository publishes an OSS business model for operating a
company-incorporation / registration-agent service on itonami.cloud.

## Classification

- Repository name: `cloud-itonami-isic-6910`
- Primary classification: ISIC Rev.5 6910
- Activity: legal activities (company-formation / registration-agent
  services fall under this class in ISIC)
- Served domain: global company incorporation execution
- Original implementation context: designed alongside `cloud-itonami-6310`
  (`gftd-talent-actor`) and `ai-gftd-itonami` as the third instance of the
  contained-intelligence + independent-governor actor pattern

## Customer

Primary customers:

- founders incorporating in a jurisdiction they are not physically present
  in (cross-border formation)
- accelerators / VC back-office teams that repeatedly incorporate
  portfolio entities
- corporate-services providers and law firms who want a governed,
  auditable intake + assessment tool instead of ad hoc spreadsheets/email
- licensed registered agents who want to operate in a new jurisdiction
  without rebuilding a compliance stack from scratch

## Problem

Company-formation SaaS and agencies today are either narrow (one country),
opaque about which legal source justifies a requirement, or willing to
push a filing through without a clear, auditable KYC/sanctions/document
trail. A customer incorporating cross-border has no way to verify why an
agent asked for a given document, or to prove after the fact that a filing
was screened and approved properly.

## Offer

Operators provide a governed company-formation intake + execution tool:

- application intake and normalization
- per-jurisdiction document checklist + fee estimate, always citing an
  official source (never a fabricated requirement)
- KYC / sanctions screening gate on every officer and shareholder
- draft LEI (ISO 17442) + registry-number assignment
- human-approved filing handoff (the actor never files or pays alone)
- immutable audit ledger of every draft, hold, and approval

The core promise: the Registrar-LLM can draft and check, but it cannot
file or pay unless a human operator -- who holds the actual jurisdiction
license and liability -- approves.

## Revenue

Operators can sell:

- per-incorporation execution fee (intake through filing handoff)
- jurisdiction-pack licensing: a maintained, spec-cited requirement
  catalog for a specific country, kept current
- managed hosting: monthly subscription per tenant (accelerator, law firm)
- KYC/sanctions-screening add-on (integration with a real screening
  provider is the operator's responsibility)
- compliance package: audit export, retention, security review

| Package | Customer | Price shape |
|---|---|---|
| Per-filing | individual founder | flat fee per incorporation |
| Jurisdiction pack | corporate-services provider | subscription per country covered |
| Managed tenant | accelerator / law firm back office | monthly platform fee |
| Managed Starter | one corporate-services / law-firm back office, unlimited staff seats | ¥40,000/月 flat |
| Operator enablement | new registered agent | training + certification |

**Market-anchored (2026-08-10)**: benchmarked against 6 real competitor
products, priced for an illustrative 10-seat corporate-services / law-firm
back office running 5–20 incorporations a month across a few jurisdictions.
**5 of the 6 publish real numbers** (one of them only partially):

| Product | Discloses | Published price | Source |
|---|---|---|---|
| Athennian (entity management for law firms / corporate services) | partial — 1 of 3 tiers | Essentials **$25,000/year**; Professional & Enterprise are "Contact Sales for Pricing" | <https://www.athennian.com/pricing> |
| Clio Manage (law-firm matter/practice management) | yes | EasyStart **$39**, Essentials **$69**, Advanced **$99**, Complete **$129** — per user/month | <https://directory.lawnext.com/products/clio-manage/pricing/> |
| ロイオズ LOIOZ (JP law-firm practice cloud) | yes | ライト **968円**, ベーシック **1,628円**, フル **4,378円** — 1ライセンス/月 (税込) | <https://www.loioz.co.jp/price/> |
| Stripe Atlas (US incorporation execution) | yes | **$500** one-time setup (incl. first-year registered agent) + **$100**/year thereafter | <https://stripe.com/atlas> |
| Firstbase.io (incorporation + back office) | yes | Start **$399** one-time; Firstbase One **$199**/month (billed yearly at $2,388); Agent Autopilot **$299**/year per state | <https://www.firstbase.io/pricing> |
| freee会社設立 (JP formation tool) | yes | **0円** for the tool itself (statutory fees separate: 株式会社 15万円 / 合同会社 6万円) | <https://www.freee.co.jp/launch/> |

Converting at ~¥150/$ for that 10-seat back office: Athennian Essentials
lands at **~¥312,500/月**; Clio Manage at 10 seats spans **¥58,500–193,500/月**;
LOIOZ at 10 licences spans **¥9,680–43,780/月**. Stripe Atlas and Firstbase
Start are per-formation, not monthly (**¥75,000/件** and **¥59,850/件**), and
Firstbase One is **¥29,850/月 per entity**. Excluding the free tool, the real
measured band for this customer is **¥43,780–312,500/月**.

**¥40,000/月 sits at the low end of that band, deliberately.** This actor is
neither an entity-lifecycle platform (no minute book, no cap table, no
document generation, no e-filing integration — what Athennian sells) nor a
firm-wide practice system (no time tracking, no billing, no trust accounting,
no document management — what Clio sells). It is one lane: intake
normalisation → a per-jurisdiction checklist where **every requirement cites
an official source** → a KYC/sanctions gate on every officer and shareholder
→ a human-approved filing handoff → an immutable ledger. Pricing it at
Athennian's level would be charging platform money for a single control.
Pricing it below LOIOZ would also be wrong: LOIOZ is cheap per seat but ships
no sanctions screening, no spec-basis requirement, and no audit ledger — and
what this actor actually sells is not clerical speed but the fact that a
**fabricated jurisdiction requirement or a sanctions hit forces a hold that
approval cannot override**, and that the refusal itself is evidence. The
ceiling is further held down by a market fact: in Japan the formation
workflow itself is given away at ¥0 by freee and its peers as a funnel into
accounting SaaS, so "help with the setup paperwork" does not command a price
here on its own. On the operator's side, one Stripe-Atlas-equivalent filing
fee (¥75,000/件) a month more than covers the subscription.

Two figures were found but **not adopted**, for honesty: Clio's own
`clio.com/pricing` returns HTTP 403 to automated fetch, so the numbers above
come from the LawNext directory listing, and third-party aggregators disagree
on the Essentials tier ($69 / $79 / $89) — the band ($39–$129) is used rather
than any single mid-tier figure. Athennian's two upper tiers are genuinely
non-public. Diligent Entities and CSC, the other entity-management incumbents,
publish nothing at all. **That opacity is an observation, not a gap**: the
priced-in-public part of this market is the low end, and the part that most
resembles what this actor governs is the part nobody quotes.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥40,000/月 flat) is available now —
[**subscribe to Managed Starter**](https://buy.stripe.com/3cI8wQ1Ib7pme9O1zAeEo0h).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. After subscribing, open an
[issue](https://github.com/cloud-itonami/cloud-itonami-isic-6910/issues/new)
to arrange managed-tenant setup (manual fulfilment today, no automated
onboarding yet). **No corporate-services provider, law firm or accelerator
has claimed or subscribed to this tier yet — this is a live, working checkout
with zero paid tenants, not a claim of existing revenue.** Nothing in this
tier grants a registered-agent or legal licence: the subscriber still supplies
the jurisdiction licence, the real KYC/AML programme, and the liability.

## Unit Economics

Track these numbers for every operator:

- setup hours per new jurisdiction added to `formation.facts`
- LLM cost per intake/assessment/screening operation
- KYC/sanctions-screening provider cost per officer
- human-approval hours per filing
- incident and audit hours
- gross margin after infrastructure, screening-provider and support costs

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish an additional jurisdiction pack (with a real official
  spec-basis citation)
- create a local operator business

itonami.cloud should require certification -- including proof of the
jurisdiction's actual company-formation / registered-agent license --
before listing an operator as a trusted provider or routing customer
leads.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples, jurisdiction packs |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review, including jurisdiction licensing proof |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

```edn
{:itonami.blueprint/id "cloud-itonami-isic-6910"
 :itonami.blueprint/name "Global Incorporation Actor"
 :itonami.blueprint/isic-rev5 "6910"
 :itonami.blueprint/domain :legal/company-formation
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-6910"
 :itonami.blueprint/status :public-oss}
```

## Non-Negotiables

- Do not commit real customer/officer identification documents or
  screening results.
- Do not bypass the RegistrarGovernor for a filing or payment.
- Do not add a jurisdiction to `formation.facts` without a real,
  citable official source.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator, and do not operate in a jurisdiction without the license that
  jurisdiction actually requires of a registered agent / formation
  professional.
