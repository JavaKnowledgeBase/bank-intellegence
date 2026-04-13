"""
System health and stats endpoints.
"""
from __future__ import annotations

import time
from fastapi import APIRouter, Request, Depends
from api.middleware.auth import get_current_user, Principal
from core.database import check_health as db_health

router = APIRouter(prefix="/system", tags=["system"])
_start_time = time.time()


@router.get("/health")
async def system_health(request: Request):
    db_ok = await db_health()
    redis_ok = False
    kafka_ok = False

    try:
        cache = request.app.state.cache
        redis_ok = await cache.check_health()
    except Exception:
        pass

    status = "healthy" if (db_ok and redis_ok) else "degraded"
    return {
        "status": status,
        "uptime_seconds": int(time.time() - _start_time),
        "components": {
            "database": "ok" if db_ok else "error",
            "redis": "ok" if redis_ok else "error",
            "kafka": "ok" if kafka_ok else "not_connected",
        },
        "version": "2.0.0",
    }


@router.get("/stats")
async def system_stats(
    request: Request,
    principal: Principal = Depends(get_current_user),
):
    from sqlalchemy import select, func
    from core.database import get_db_session
    from models.issue import IssueORM
    from models.app_config import AppConfigORM

    stats = {"apps": 0, "issues": {}, "budgets": []}

    try:
        from sqlalchemy.ext.asyncio import AsyncSession
        async for db in get_db_session():
            app_count = await db.execute(select(func.count()).select_from(AppConfigORM))
            stats["apps"] = app_count.scalar() or 0

            issue_counts = await db.execute(
                select(IssueORM.status, func.count())
                .group_by(IssueORM.status)
            )
            stats["issues"] = {row[0]: row[1] for row in issue_counts.fetchall()}
            break
    except Exception:
        pass

    try:
        budget_ctrl = request.app.state.budget
        stats["budgets"] = await budget_ctrl.get_all_usage()
    except Exception:
        pass

    stats["ws_connections"] = request.app.state.ws_manager.connection_count
    return stats
