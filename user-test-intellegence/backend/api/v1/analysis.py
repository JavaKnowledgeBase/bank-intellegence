from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from services.analysis import AnalysisService
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/analysis", tags=["analysis"])
service = AnalysisService()


@router.get("/failures")
def get_failure_analysis(_: UserSession = Depends(get_current_user)) -> dict:
    return service.failure_analysis()


@router.get("/suggestions/{service_name}")
def get_generation_suggestions(service_name: str, _: UserSession = Depends(get_current_user)) -> dict:
    return service.generation_suggestions(service_name)


@router.get("/scout-stats")
def get_scout_stats(_: UserSession = Depends(get_current_user)) -> dict:
    return service.scout_stats()


@router.get("/execution-events")
def get_execution_events(suite_run_id: str | None = None, _: UserSession = Depends(get_current_user)) -> dict:
    return service.execution_events(suite_run_id=suite_run_id)
