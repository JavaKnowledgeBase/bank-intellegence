from __future__ import annotations

from fastapi import APIRouter

from models.test_run import ShardResultIn
from services.execution import ExecutionService

router = APIRouter(prefix="/api/v1/internal", tags=["internal"])
service = ExecutionService()


@router.post("/shard-result", status_code=202)
async def shard_result(payload: ShardResultIn) -> dict:
    await service.record_shard_result(payload)
    return {"accepted": True}
