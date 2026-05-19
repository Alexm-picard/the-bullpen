---
description: Kick off the multi-model UI design loop for a named screen
argument-hint: <screen-name> (e.g., "ops-dashboard", "model-registry", "game-live-view")
---

Invoke the `ui-design-loop` agent for the screen:

$ARGUMENTS

Follow the full loop:
1. Phase 1 — Parallel generation (Claude proposal + Stitch proposal)
2. Phase 2 — Cherry-pick and synthesize (ask me what I like from each)
3. Phase 3 — Spec approval gate (wait for explicit approval before code)
4. Phase 4 — Code generation (React + Mantine + Tailwind, no hex codes, TanStack Query, polling not WebSockets)
5. Phase 5 — Playwright verify loop (max 3 self-critique iterations)

Do not skip the approval gate. Do not claim success without Playwright screenshots.
