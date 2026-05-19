---
description: Multi-agent ML review — runs ml-leakage-auditor and python-training-reviewer in parallel on current diff
---

Run a parallel ML review of the current uncommitted changes (or last commit if working tree is clean).

Invoke in parallel:
1. The `ml-leakage-auditor` agent on the diff
2. The `python-training-reviewer` agent on the same diff

Wait for both. Synthesize their outputs into a single report with:
- Combined VERDICT (FAIL > BLOCKED > APPROVED WITH NOTES > APPROVED)
- All BLOCKERS from both, deduplicated by file:line
- All NOTES from both, deduplicated
- The combined RUN BEFORE MERGE command list

If either returns FAIL or BLOCKED, do not suggest the change is mergeable.
