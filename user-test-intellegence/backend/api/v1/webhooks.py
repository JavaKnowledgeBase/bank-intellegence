from __future__ import annotations

from fastapi import APIRouter

from models.test_run import SuiteRunCreate
from services.execution import ExecutionService

router = APIRouter(prefix="/api/v1/webhooks", tags=["webhooks"])
execution_service = ExecutionService()


@router.post("/github")
async def github_webhook(payload: dict) -> dict:
    service = payload.get("service", "customer-agent-service")
    suite_run_id = await execution_service.start_suite(
        service,
        SuiteRunCreate(
            trigger="deploy",
            trigger_actor=payload.get("author", "github"),
            commit_sha=payload.get("commit"),
            branch=payload.get("branch", "main"),
        ),
    )
    return {"accepted": True, "suite_run_id": suite_run_id}
