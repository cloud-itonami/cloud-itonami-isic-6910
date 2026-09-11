# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-6910`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6910
cd cloud-itonami-isic-6910
kbb -M:dev:test
kbb -M:dev:run
```

The default demo uses synthetic applications and officers. Production
customer applications, officer identification and screening results must
stay outside the repository and be injected through a store adapter.

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one operator owns infrastructure and customer data |
| Managed tenant | an operator hosts for an accelerator / law firm |
| Certified operator | itonami.cloud has reviewed license, security and process controls |

## 3. Production Checklist

- confirm you (the operator) hold whatever license the target
  jurisdiction requires of a registered agent / formation professional --
  this software does not grant or substitute for one
- replace demo data with a customer-owned store
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or a secret
  manager
- integrate a real KYC/sanctions-screening provider behind
  `formation.registrarllm`'s `:kyc/screen` path
- extend `formation.facts/catalog` for every jurisdiction you serve, each
  entry citing the jurisdiction's own official registry as `:provenance`
- run `kbb -M:dev:test`
- run `kbb -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- get written approval for handling customer identification documents

## 4. Sales Motion

Start with a narrow offer:

1. one jurisdiction, one entity type
2. prove the governed document-checklist + KYC-screening flow
3. run one filing through human approval end-to-end
4. export the audit ledger for the customer's own records
5. expand to a second jurisdiction only after the first is repeatable

Avoid selling "any country, any structure" before the jurisdiction pack
and the human-approval workflow for that jurisdiction actually exist and
have been exercised.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- proof of the operator's jurisdiction-specific license/registration
  where the jurisdiction requires one
- written data-flow diagram, including where KYC documents are stored
- backup/restore evidence
- incident contact and response window
- proof that every filing/payment passes through a human approval step
  (never bypassed, never auto-committed -- see README `Actuation`)
- proof that real customer identification documents are not stored in Git
- customer-facing support terms

## 6. Operator Responsibilities

Operators are responsible for:

- holding the actual license/registration a jurisdiction requires
- customer consent and lawful basis for KYC data processing
- the real integration with each jurisdiction's government filing portal
  and fee-payment method
- secure infrastructure and tenant isolation
- human approval workflow staffing (someone has to actually review and
  approve each filing)
- data-retention policy for identification documents
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator licensed, KYC-compliant, or legally authorized to file on
anyone's behalf by itself.
