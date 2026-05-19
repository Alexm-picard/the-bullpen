---
description: Kick off the conversational decision flow for The Bullpen, ending in a numbered docs/decisions.md entry
argument-hint: <one-line description of the decision being made>
---

Invoke the `lock-decision` skill for The Bullpen. The decision to discuss is:

$ARGUMENTS

Follow the skill's procedure: frame the decision with 2–4 concrete options, converge with me through discussion, confirm explicit agreement, then hand off to the `decision-recorder` agent to draft the numbered entry and any ripple updates to `docs/design.md` / `docs/plan.md`. Do not write any files until I approve the diff.
