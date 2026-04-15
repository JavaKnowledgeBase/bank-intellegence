from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user, require_role
from core.repositories import ImprovementRepository
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/self-improvement", tags=["self-improvement"])
repo = ImprovementRepository()


@router.get("/proposals")
def list_proposals(user: UserSession = Depends(get_current_user)) -> dict:
    require_role(user, {"admin"})
    return {"items": [item.model_dump(mode="json") for item in repo.list()]}
