---
name: api-design
description: Design or review a FastAPI REST endpoint — route structure, request/response schemas, error handling, auth, and Pydantic models. Trigger when the user asks to design, spec, or review an API endpoint or feature.
---

FastAPI API design checklist for StudyForesight:

**Route structure:**
- Routes live in `api/` — one file per domain (documents, study, user, etc.)
- Mount in `api/main.py` with prefix: `app.include_router(router, prefix="/api/v1")`
- Resources are nouns: `/documents`, `/flashcards`, `/study-sessions`
- Actions as POST sub-resources: `/documents/{id}/reprocess` not `PATCH` with `{action: "reprocess"}`

**Auth (always):**
```python
from api.auth import get_current_user
from models.user import User

@router.get("/resource")
async def get_resource(current_user: User = Depends(get_current_user)):
    ...
```
Every protected endpoint uses `Depends(get_current_user)`.

**Pydantic schemas:**
- Request model: `class CreateFooRequest(BaseModel):`
- Response model: `class FooResponse(BaseModel):` with `model_config = ConfigDict(from_attributes=True)`
- Use `response_model=FooResponse` on the route decorator

**Error responses** (FastAPI standard):
```python
raise HTTPException(status_code=404, detail="Document not found")
raise HTTPException(status_code=422, detail="Validation failed")
raise HTTPException(status_code=429, detail="Rate limit exceeded")
```

**DB session:**
```python
from models.database import get_db
from sqlalchemy.ext.asyncio import AsyncSession

@router.post("/resource")
async def create_resource(
    req: CreateResourceRequest,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    ...
```

**Pagination for list endpoints:**
```python
@router.get("/documents")
async def list_documents(
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    ...
):
```

**Internal endpoints** (called by QStash):
- Prefix: `/internal/`
- Must verify QStash signature — see `api/internal.py` for pattern
- Not exposed to frontend
