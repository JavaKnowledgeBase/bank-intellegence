from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from core.repositories import ScoutRepository
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/scout", tags=["scout"])
repo = ScoutRepository()


@router.get("/observations")
def list_observations(_: UserSession = Depends(get_current_user)) -> dict:
    return {"items": [item.model_dump(mode="json") for item in repo.list()]}


@router.post("/run")
def trigger_scout(_: UserSession = Depends(get_current_user)) -> dict:
    items = [item.model_dump(mode="json") for item in repo.list()]
    return {"status": "scheduled", "accepted_today": sum(1 for item in items if item["safety_status"] == "accepted")}
