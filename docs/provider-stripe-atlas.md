# Stripe Atlas provider profile

This profile maps Stripe Atlas into the ISIC 6910 company-formation workflow.
It documents an integration option; it does not make Stripe Atlas the
authoritative source for legal requirements.

## Formation record

Capture these values from executed formation documents:

- exact legal name and entity type
- formation jurisdiction, date, and file number
- registered agent and registered office
- EIN status
- ownership percentages and control person

The registered office is a legal address, not automatically the principal
operating address. Never infer or invent a US physical presence.

## Evidence boundary

Keep certificates, agreements, EIN letters, identity documents, address
evidence, and beneficial-owner data in an approved encrypted vault. Workflow
records contain only opaque vault references and redacted status metadata.

## Banking handoff

After formation, issue a human-approved handoff containing the legal-name
assertion, formation-document references, EIN status, owner/control-person
roles, and physical operating address. A banking provider performs its own
eligibility and KYC review; formation approval never implies bank approval.

Stripe-specific API contracts belong in `kotoba-lang/com-stripe`.
Bank-provider contracts belong in their reverse-domain repositories.
