# Contributing

`cloud-itonami-isic-6910` accepts contributions to the OSS actor, governor
tests, documentation, jurisdiction packs and open business blueprint.

## Development

```bash
kbb -M:dev:test
kbb -M:lint
```

Keep changes small and include tests for governor, phase, registry or
facts-coverage behavior.

## Rules

- Do not commit real customer applications, credentials, identification
  documents or screening results.
- Keep production filings and payments behind RegistrarGovernor AND the
  phase table -- never remove `:filing/submit` from a governor hard-check
  or add it to a phase's `:auto` set.
- Treat this as a high-risk domain: add tests for spec-basis, sanctions,
  document-completeness and audit logging with every change.
- A new jurisdiction entry in `formation.facts/catalog` MUST cite a real
  official source (`:provenance`) -- do not add a placeholder.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor or phase invariant is affected
- how it was tested
- whether operator or certification docs need updates
