from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from core.auth import get_current_user, require_role
from core.repositories import SuiteRunRepository
from models.auth import UserSession
from models.test_run import SuiteRun, SuiteRunCreate
from services.execution import ExecutionService

router = APIRouter(prefix="/api/v1/runs", tags=["runs"])
repo = SuiteRunRepository()
service = ExecutionService()


@router.get("/suites", response_model=list[SuiteRun])
def list_suite_runs(_: UserSession = Depends(get_current_user)) -> list[SuiteRun]:
    return repo.list()


@router.get("/suites/{suite_run_id}", response_model=SuiteRun)
def get_suite_run(suite_run_id: str, _: UserSession = Depends(get_current_user)) -> SuiteRun:
    suite = repo.get(suite_run_id)
    if suite is None:
        raise HTTPException(status_code=404, detail="Suite run not found")
    return suite


@router.post("/suite/{service_name}", status_code=202)
async def start_suite_run(service_name: str, payload: SuiteRunCreate, user: UserSession = Depends(get_current_user)) -> dict:
    require_role(user, {"qa", "admin"})
    suite_id = await service.start_suite(service_name, payload)
    return {"suite_run_id": suite_id}
