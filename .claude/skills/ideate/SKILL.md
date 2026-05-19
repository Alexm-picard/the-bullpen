---
name: ideate
description: Generate high-quality, product-ready feature ideas for StudyForesight. Supports three modes — quick (3-5 fast ideas), deep (1 fully spec'd idea), roadmap (grouped themes). Invoke with /ideate [mode] [focus-area]. Focus areas: retention | engagement | monetization | ml | ux | integration | any. Defaults: mode=quick, focus=any.
---

## Ideation Protocol

Parse the invocation args:
- First arg: `quick` | `deep` | `roadmap` (default: `quick`)
- Second arg: `retention` | `engagement` | `monetization` | `ml` | `ux` | `integration` | `any` (default: `any`)

Example: `/ideate deep ml` → one deeply specced ML feature idea.

---

## Before generating ideas

1. **Check the dedup log** at `.claude/agent-memory/ideate-log.md`. Do not regenerate any idea whose slug already appears there.
2. **Ground yourself in what exists**: the platform does RAG Q&A, SM-2 spaced repetition, PDF/DOCX/TXT ingestion, flashcard generation via Cloudflare Workers AI, Clerk auth, Google Drive sync, QStash async processing, Pinecone vector store, Upstash Redis cache.
3. **Avoid generic ideas** (e.g. "add dark mode", "add notifications", "improve performance"). Ideas must be specific, buildable, and non-obvious.
4. **Bias toward**: retention mechanics, AI-powered personalisation, engagement loops, and monetisation levers that fit a solo-dev/small-team budget.

---

## Idea Quality Bar

Reject any idea that:
- Could apply to any SaaS ("add Slack integration", "add dark mode")
- Requires rebuilding core infrastructure to work
- Is already obviously in the codebase (RAG, SM-2, document upload)
- Has no clear user behaviour change ("users will learn faster")

Accept ideas that:
- Exploit the specific combination of RAG + SM-2 + document context in a novel way
- Create a habit loop or return trigger
- Have a clear first implementation step (a specific file to change or endpoint to add)
- Can be validated with a single A/B metric

---

## MODE: quick

Generate **3 to 5 ideas**. For each idea output exactly this block:

```
### [IDEA-NNN] <Title>

**Problem**: One sentence — what specific user pain or drop-off does this address?
**Solution**: Two to three sentences — what the feature does, concretely.
**Value lever**: retention | engagement | monetisation | ml | ux | integration
**Complexity**: Low | Medium | High
**Impact**: Low | Medium | High
**Score**: <calculated below>
**Stack touched**: comma-separated subset of: backend, frontend, ML/AI, DB, infra, cache, vector-db
**First step**: The single most concrete next action (e.g. "Add POST /api/v1/study-sessions/{id}/hints endpoint in api/study.py")
```

Score formula (1–10):
- Impact weight: Low=1, Medium=2, High=3 → multiply by 2
- Complexity penalty: Low=1, Medium=2, High=3
- Score = min(10, (impact_weight × 2 + 3 - complexity_weight) × 10/9)
- Round to one decimal. High impact + low complexity → ~10. Low impact + high complexity → ~2.

After generating all ideas, append each idea's slug (format: `IDEA-NNN: <Title>`) to `.claude/agent-memory/ideate-log.md` so they are not regenerated in future sessions.

---

## MODE: deep

Generate **exactly one idea** with full product and technical spec.

Output this full template:

```
# [IDEA-NNN] <Title>
**Value lever**: retention | engagement | monetisation | ml | ux | integration
**Complexity**: Low | Medium | High  |  **Impact**: Low | Medium | High  |  **Score**: X.X

---

## Problem
Two to four sentences: the specific user problem, with evidence from how the system currently works or fails.

## Proposed Solution
A clear product description. What does the user see and do? What does the system do behind the scenes?

## Why Now / Why This Platform
Why is StudyForesight specifically well-positioned to build this? What existing capability (RAG pipeline, SM-2, embeddings, Pinecone) does this extend?

## User Story
> As a [persona], I want [action] so that [outcome].

## Technical Design
### New / Changed Files
List each file with a one-line description of what changes.

### New Endpoints (if any)
Method + path + one-line description of request/response.

### Data Model Changes (if any)
New columns, tables, or Pinecone metadata fields.

### Key Implementation Notes
- Bullet-point gotchas, ordering constraints, or non-obvious choices.
- Call out any interaction with: clerk_user_id vs user_id UUID, Pinecone 1000-char limit, QStash idempotency, async constraints.

### New Env Vars (if any)
List any new config values that would go in api/config.py Settings.

## Risks & Mitigations
| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| ...  | Low/Med/High | ... |

## Success Metric
One measurable signal that tells you the feature is working (e.g. "D7 retention increases by X%", "avg flashcards reviewed per session increases").

## Next Step
The exact first action: a file to open, an endpoint to stub, a migration to write.

## Suggested Handoff
Which agent to invoke next: backend-engineer | frontend-engineer | db-specialist | ml-engineer | test-engineer
```

Append the idea slug to `.claude/agent-memory/ideate-log.md`.

---

## MODE: roadmap

Generate **5 to 8 ideas** grouped into **3 themed quarters**. Use this structure:

```
## Quarter N — <Theme Name>
*Goal: one sentence describing the user outcome this quarter advances*

### [IDEA-NNN] <Title> — Complexity: X | Impact: X | Score: X.X
One paragraph: problem, solution, why it fits this quarter's theme.
**First step**: ...

[repeat for each idea in this quarter]
```

Choose quarter themes from: Foundation (reliability, data quality), Growth (acquisition, engagement), Monetisation (conversion, retention of paid users), Intelligence (ML/AI improvements), or Platform (integrations, ecosystem).

Order ideas within a quarter by score (descending). Order quarters by dependency (earlier quarters unblock later ones).

Append all idea slugs to `.claude/agent-memory/ideate-log.md`.

---

## Anti-patterns to actively avoid

- "Add push notifications" without specifying the exact trigger and message content
- "Improve the AI" without naming which model, which prompt, which metric
- "Mobile app" as a standalone idea (too large, not a feature)
- Anything that requires OCR or Speech-to-Text (these are stubs, not implemented)
- Ideas that require `oauth_states` to survive a restart (it's in-memory, known tech debt)

---

## Personas to keep in mind

1. **The Cramming Student** — uploads lecture slides 2 days before an exam. Needs fast, dense flashcard generation and a "cram mode" that overrides SM-2 spacing.
2. **The Lifelong Learner** — uploads books/articles weekly. Needs knowledge graph to see connections across documents, long-term retention tracking.
3. **The Team Lead** — wants to share a document deck with their team and track who has reviewed what. Needs shared decks and per-user progress.
4. **The Power User** — wants to tune SM-2 parameters, export data, use the API. Needs data portability and configurability.