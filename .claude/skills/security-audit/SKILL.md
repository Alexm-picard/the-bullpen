---
name: security-audit
description: Security review of code for common vulnerabilities specific to StudyForesight. Trigger when the user asks for a security review or audit, or before any feature involving auth, file upload, payments, webhooks, or user data.
---

Security checklist for StudyForesight:

**Authentication / Authorisation:**
- [ ] All protected routes use `Depends(get_current_user)`
- [ ] No endpoint accidentally skips auth dependency
- [ ] IDOR check: can user A access user B's documents/flashcards by changing an ID?
- [ ] `Document.user_id` (UUID) compared against `current_user.user_id` (UUID) — not mixed with Clerk string ID
- [ ] Internal endpoints (`/internal/*`) verify QStash `upstash-signature` header
- [ ] Clerk webhook (`/webhooks/clerk`) verifies `svix-signature` header

**Input validation:**
- [ ] All request bodies validated via Pydantic models
- [ ] File uploads: type validation (`content_type`), size limits enforced
- [ ] No SQL string concatenation — SQLAlchemy parameterised queries only
- [ ] User-provided filenames sanitised before storage (no path traversal)

**CORS:**
- [ ] `allow_origins` is a specific list from `settings.allowed_origins` (not `["*"]`)
- [ ] `allow_origins=["*"]` with `allow_credentials=True` — this is INVALID and must not exist

**Secrets:**
- [ ] No hardcoded API keys, tokens, or passwords in any file
- [ ] `.env` is in `.gitignore`
- [ ] All secrets accessed via `api/config.py` Settings (pydantic-settings)

**Pinecone / Vector DB:**
- [ ] Namespace isolation: queries scoped to `user_id` namespace to prevent cross-user data leakage
- [ ] Metadata does not include full document text (truncated to 1000 chars)

**Redis / Rate Limiting:**
- [ ] Rate limit keys include user ID to prevent cross-user interference
- [ ] `zadd` collision fix in place (atomic operation, not read-then-write)

**Data exposure:**
- [ ] API responses do not leak internal fields (internal IDs, other users' data)
- [ ] 500 error responses sanitised — no stack traces to clients (global exception handler in `api/main.py`)
- [ ] Audit log (`services/audit_log.py`) captures sensitive operations

**Dependencies:**
- [ ] `pip audit` or `safety check` run against `requirements.txt`
- [ ] No packages with known critical CVEs
