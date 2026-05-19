---
name: debug-strategy
description: Structured approach to debugging any bug, error, or unexpected behaviour in StudyForesight. Trigger when the user reports a bug, unexpected output, test failure, or asks "why is X happening".
---

Apply this debugging protocol:

1. **Reproduce first** — Can you reproduce it consistently? What are the exact steps?
2. **Read the error message fully** — Stack trace, HTTP status, FastAPI validation error detail. Don't skim.
3. **Isolate** — Binary search the problem. Which layer is failing: route handler, service, worker, DB, external API?
4. **Check assumptions** — Log/print the values you *assume* are correct. Are they?
5. **Recent changes** — `git log --oneline -20`. What changed recently?
6. **External state** — Env vars set? Redis/Pinecone connection healthy? Clerk JWKS reachable? Supabase up?
7. **Minimal reproduction** — Can you reproduce in a single pytest test without the full system?

Common StudyForesight failure modes to check:
- Auth: Is `clerk_user_id` being looked up (not `user_id` UUID)?
- DB: Is `next(get_db())` being used instead of `Depends(get_db)`? (session leak)
- CORS: Is `allow_origins=["*"]` combined with `allow_credentials=True`?
- Idempotency: Did `try_claim()` return False (already processing)?
- Pinecone: Is metadata content > 1000 chars causing upsert failures?
- QStash: Is the signature verification failing on `/internal/*` routes?
- Embeddings/LLM: Is the singleton being re-instantiated instead of reused?

Output format:
```
Debug Report
Hypothesis: [what you think is wrong]
Evidence: [logs, stack traces, relevant code]
Root cause: [actual cause found]
Fix: [change made]
Prevention: [test added / anti-pattern to document]
```
