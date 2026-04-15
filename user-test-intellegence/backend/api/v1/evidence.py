from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user, require_role
from core.evidence_generator import TestEvidenceGenerator
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/evidence", tags=["evidence"])
service = TestEvidenceGenerator()


@router.get("")
def list_evidence(user: UserSession = Depends(get_current_user)) -> dict:
    require_role(user, {"qa", "admin"})
    return {"items": service.list_evidence()}


@router.get("/{suite_run_id}")
def get_evidence(suite_run_id: str, user: UserSession = Depends(get_current_user)):
    require_role(user, {"qa", "admin"})
    payload = service.get_evidence(suite_run_id)
    if payload is None:
        return {"detail": "Evidence not found"}
    return payload
