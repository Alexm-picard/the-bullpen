---
name: lock-decision
description: The conversational decision flow that ends in an appended entry in docs/decisions.md. Trigger when the user says "lock this", "let's decide", "finalize the decision", or wants to record an architectural or process decision.
---

# lock-decision

Decisions in The Bullpen are conversational, then numbered and append-only. This skill operationalizes that.

## The rule (from CLAUDE.md, working-style section)

> No decision gets locked into `docs/decisions.md` until we've gone back-and-forth and explicitly agreed. No silent "I'll just pick X and tell you." Claude proposes, user pushes back, we converge, *then* we update the log.

## Procedure

### Phase 1 — Frame
- Restate the decision being made in one sentence
- List 2–4 concrete options (no "we could also...", be specific)
- For each option, give: rationale, trade-off, who-else-does-this evidence if relevant

### Phase 2 — Converge
- Ask the user which way they lean and why
- If their reasoning relies on an assumption that might be wrong, surface it
- If their choice contradicts a locked decision in `docs/decisions.md`, **stop** and explicitly call out that this is a reversal
- Iterate until you and the user agree

### Phase 3 — Confirm agreement + decide layer
- Restate the chosen option in one sentence
- State the one-line rationale
- **Ask whether this warrants a full ADR** in addition to the `decisions.md` entry. Criteria:
  - 2+ meaningful alternatives were considered
  - The "why" needs more than one sentence
  - The decision affects architecture or a core abstraction
  - The decision constrains future work in non-obvious ways
  
  If unsure, propose the ADR — the cost of writing one is low; the cost of *not* writing one when future-you needs the context is high.
- Ask: "Lock this as decision [N]" (+ "and ADR-NNNN" if applicable)? — use the next number after reading `docs/decisions.md` and `docs/adr/`
- Wait for explicit yes

### Phase 4 — Hand off to decision-recorder
- Invoke the `decision-recorder` agent with the drafted `decisions.md` entry (and ADR if applicable)
- Include: ripple updates to `docs/design.md`, `docs/plan.md`, `docs/phase-status.json` if any
- For ADRs, the decision-recorder will use `docs/adr/TEMPLATE.md` as the starting point
- Wait for the agent's diff and show it to the user before any file is written

### Phase 5 — Commit
- Suggest a Conventional Commits message: `docs: lock decision [N] — <short summary>` (add `+ ADR-NNNN` if both)
- If the change is a reversal, prefix `docs!: reverse decision [M] via [N] — <reason>` and note any ADR being superseded
- Don't commit yourself unless asked

## Anti-patterns to refuse

- "Just record what I said" without going through Phase 1 — push back, run the loop
- Empty rationale ("for clarity", "to improve things") — push back, ask for the specific reason or past incident
- Skipping a reversal callout when contradicting a prior decision — never lose the history
- Editing past entries — the git hook will block it; never propose it
