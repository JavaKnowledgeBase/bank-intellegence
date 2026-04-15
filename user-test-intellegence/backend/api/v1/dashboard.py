from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from services.dashboard import DashboardService
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/dashboard", tags=["dashboard"])
service = DashboardService()


@router.get("/summary")
def get_summary(_: UserSession = Depends(get_current_user)) -> dict:
    return service.summary()
