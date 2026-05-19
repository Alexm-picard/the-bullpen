---
name: refactoring
description: Systematic refactoring of existing Python or TypeScript code — reduce complexity, improve readability, extract abstractions, eliminate duplication. Trigger when the user asks to refactor, clean up, simplify, or improve code quality.
---

Refactoring protocol (never breaks working behaviour):

**Before touching anything:**
1. Confirm tests exist and pass: `venv/bin/python -m pytest tests/ -q`
2. Commit current state: `git commit -m "chore: pre-refactor checkpoint"`
3. Define the goal: what specific problem are we solving?

**Refactoring patterns to apply (in order of impact):**

1. **Extract service/function** — Any route handler doing business logic → extract to `services/`
2. **Eliminate magic strings/numbers** — Named constants, Pydantic enums
3. **Reduce nesting** — Early returns over nested if/else
4. **Consolidate DB queries** — Repeated `select()` patterns → shared repository function
5. **Singleton extraction** — Services/clients re-instantiated in multiple places → singleton in module
6. **Separate concerns** — Route handler should only: validate input, call service, return response
7. **Replace print() with logger** — `logger = logging.getLogger(__name__)`
8. **Fix session leaks** — `next(get_db())` → `Depends(get_db)` or async context manager

**StudyForesight-specific patterns to enforce:**
- `LLMService`, `RAGQueryEngine`, `EmbeddingGenerator` must be singletons — extract to module-level
- DB session must come from `Depends(get_db)` — never call `next(get_db())`
- All `print()` → `logger.info/warning/error()`
- Duplicate study session creation logic → `_get_or_create_active_session()` helper

**What NOT to refactor:**
- Code you don't understand yet — read it first
- Working code without tests — add tests first
- More than one concern per PR — one refactoring goal per commit

Output:
- List of changes made
- Before/after description
- Tests run to confirm no behaviour change
