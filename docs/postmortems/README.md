# Postmortems

Drift incident postmortems live here. The reusable template is in
[`docs/runbooks/drift-postmortem-template.md`](../runbooks/drift-postmortem-template.md);
copy it to `YYYY-MM-DD-<slug>.md` when the first drift alert fires.

## Process

1. Drift detector (Phase 3c) fires → Discord PAGE or NOTICE.
2. Operator copies the template to this directory with date-prefixed
   filename, **fills in alongside the investigation** — don't wait.
3. Resolution applied → commit the postmortem to git. Don't sanitize.
4. Decision worth carrying forward → log under
   [`docs/decisions.md`](../decisions.md). Architecturally substantive
   → raise an ADR under [`docs/adr/`](../adr/).
5. Operator update worth carrying forward → update the relevant
   runbook in [`docs/runbooks/`](../runbooks/).

## Drill events

For drill-fired synthetic drift events, prefix the filename with
`drill-` and put a banner at the top of the postmortem
("Drill — not real production drift"). Drill postmortems still earn
their place in this directory — they're the only thing keeping the
drift detector itself honest before the first real event lands.

## Why this directory exists

Centerpiece resume artifact (decision [82]). Operating through at
least one MLB season for a real drift postmortem is the original
"why" of the project — these files are the receipts.
