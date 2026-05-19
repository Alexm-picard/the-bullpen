---
name: pr-creation
description: Creates a well-structured pull request description from staged changes. Trigger when the user asks to create a PR, open a pull request, or summarise changes for review.
---

Steps:
1. Run `git diff main --stat` to understand scope
2. Run `git log main..HEAD --oneline` to see commits
3. Read changed files to understand intent
4. Run `venv/bin/python -m pytest tests/ -q` to confirm tests pass

PR template to generate:

```markdown
## What
[2-3 sentence summary of what this PR does]

## Why
[Business or technical motivation — link to issue if applicable]

## How
[Key implementation decisions, non-obvious choices, trade-offs made]

## Testing
- [ ] Unit tests added/updated
- [ ] Integration tests pass (`pytest tests/ -q`)
- [ ] Manually tested: [steps]

## Checklist
- [ ] No secrets or hardcoded credentials in code
- [ ] No `print()` statements — uses `logger`
- [ ] No `next(get_db())` — uses `Depends(get_db)`
- [ ] Error handling with `HTTPException`
- [ ] Migration included (if schema changed)
- [ ] `CLAUDE.md` updated (if new conventions introduced)
- [ ] Breaking changes documented

## Schema changes
[List any Alembic migrations included, or "None"]
```

After generating the PR description, remind the reviewer to check:
- Clerk auth on all new endpoints
- Pinecone metadata length if document processing changed
- Rate limiting if new public endpoints added
