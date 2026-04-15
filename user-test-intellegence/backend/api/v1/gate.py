from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from core.repositories import SuiteRunRepository
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/gate", tags=["gate"])
repo = SuiteRunRepository()


@router.get("/{service}/{commit_sha}")
def get_gate_status(service: str, commit_sha: str, _: UserSession = Depends(get_current_user)) -> dict:
    matching = [
        run
        for run in repo.list()
        if run.service == service and (run.commit_sha == commit_sha or run.commit_sha is None)
    ]
    latest = matching[0] if matching else None
    passed = bool(latest and latest.status == "passed")
    return {
        "service": service,
        "commit_sha": commit_sha,
        "passed": passed,
        "status": latest.status if latest else "unknown",
        "failures": latest.failed if latest else 0,
        "suite_run_id": latest.id if latest else None,
    }
