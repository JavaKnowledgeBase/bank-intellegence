from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user, require_role
from services.compliance import ComplianceService
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/compliance", tags=["compliance"])
compliance_service = ComplianceService()


@router.get("/report")
def get_report(month: str, service: str = "all", user: UserSession = Depends(get_current_user)) -> dict:
    require_role(user, {"admin"})
    return compliance_service.report(month=month, service=service)
