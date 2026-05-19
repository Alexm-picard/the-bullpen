---
description: Multi-agent Java review — runs java-reviewer and registry-guard (if registry files touched) on current diff
---

Run a Java review of the current uncommitted changes (or last commit if working tree is clean).

Invoke:
1. The `java-reviewer` agent on the diff — always
2. If the diff touches any file under `backend/src/main/java/net/thebullpen/baseball/registry/` or `.../inference/`, also invoke `registry-guard` in parallel

Wait for both (or one if registry untouched). Synthesize into a single report:
- Combined VERDICT (BLOCKED > APPROVED WITH NOTES > APPROVED)
- All BLOCKERS from both, deduplicated by file:line
- All SUGGESTIONS from both, deduplicated

If `registry-guard` returns BLOCKED, treat as hard block regardless of `java-reviewer` verdict — the discipline rules are non-negotiable.
