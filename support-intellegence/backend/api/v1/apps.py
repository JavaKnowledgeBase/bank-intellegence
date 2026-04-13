"""
App management REST endpoints.
POST /api/v1/apps — register (with auto-discovery)
GET  /api/v1/apps — list (filterable)
PATCH/DELETE/POST pause/resume
"""
from __future__ import annotations

import logging
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from api.middleware.auth import get_current_user, require_role, Principal
from core.database import get_db_session
from models.app_config import AppConfigORM, AppConfigCreate, AppConfigUpdate, AppConfigResponse

router = APIRouter(prefix="/apps", tags=["apps"])
logger = logging.getLogger(__name__)


@router.post("", response_model=AppConfigResponse, status_code=201)
async def register_app(
    body: AppConfigCreate,
    request: Request,
    principal: Principal = Depends(require_role("csip:engineer")),
    db: AsyncSession = Depends(get_db_session),
):
    orchestrator = request.app.state.orchestrator
    app = await orchestrator.register_app(
        {
            "url": body.url,
            "description": body.description,
            "team_id": body.team_id,
            "tier": body.tier,
            "repo_url": body.repo_url,
            "codeowners": body.codeowners,
            "created_by": principal.user_id,
        },
        created_by=principal.user_id,
    )
    return AppConfigResponse.from_orm(app)


@router.get("", response_model=list[AppConfigResponse])
async def list_apps(
    team_id: Optional[str] = Query(None),
    tier: Optional[str] = Query(None),
    status: Optional[str] = Query(None),
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    stmt = select(AppConfigORM)
    if team_id:
        stmt = stmt.where(AppConfigORM.team_id == team_id)
    if tier:
        stmt = stmt.where(AppConfigORM.tier == tier)
    if status:
        stmt = stmt.where(AppConfigORM.status == status)
    stmt = stmt.order_by(AppConfigORM.name)

    result = await db.execute(stmt)
    apps = result.scalars().all()
    return [AppConfigResponse.from_orm(a) for a in apps]


@router.get("/{app_id}", response_model=AppConfigResponse)
async def get_app(
    app_id: str,
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(AppConfigORM).where(AppConfigORM.id == app_id))
    app = result.scalar_one_or_none()
    if not app:
        raise HTTPException(404, f"App {app_id} not found")
    return AppConfigResponse.from_orm(app)


@router.patch("/{app_id}", response_model=AppConfigResponse)
async def update_app(
    app_id: str,
    body: AppConfigUpdate,
    principal: Principal = Depends(require_role("csip:engineer")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(AppConfigORM).where(AppConfigORM.id == app_id))
    app = result.scalar_one_or_none()
    if not app:
        raise HTTPException(404, f"App {app_id} not found")

    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(app, field, value)
    await db.commit()
    await db.refresh(app)
    return AppConfigResponse.from_orm(app)


@router.delete("/{app_id}", status_code=204)
async def delete_app(
    app_id: str,
    request: Request,
    principal: Principal = Depends(require_role("csip:engineer")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(AppConfigORM).where(AppConfigORM.id == app_id))
    app = result.scalar_one_or_none()
    if not app:
        raise HTTPException(404, f"App {app_id} not found")

    orchestrator = request.app.state.orchestrator
    await orchestrator._monitor.stop_monitoring(app_id)
    await db.delete(app)
    await db.commit()


@router.post("/{app_id}/pause", status_code=200)
async def pause_monitoring(
    app_id: str,
    principal: Principal = Depends(require_role("csip:operator")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(AppConfigORM).where(AppConfigORM.id == app_id))
    app = result.scalar_one_or_none()
    if not app:
        raise HTTPException(404, f"App {app_id} not found")
    app.status = "paused"
    await db.commit()
    return {"message": f"Monitoring paused for {app.name}"}


@router.post("/{app_id}/resume", status_code=200)
async def resume_monitoring(
    app_id: str,
    request: Request,
    principal: Principal = Depends(require_role("csip:operator")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(AppConfigORM).where(AppConfigORM.id == app_id))
    app = result.scalar_one_or_none()
    if not app:
        raise HTTPException(404, f"App {app_id} not found")
    app.status = "unknown"
    app.monitoring_paused_until = None
    await db.commit()
    orchestrator = request.app.state.orchestrator
    await orchestrator._monitor.start_monitoring(app)
    return {"message": f"Monitoring resumed for {app.name}"}


@router.get("/{app_id}/health")
async def get_health(
    app_id: str,
    request: Request,
    principal: Principal = Depends(get_current_user),
):
    cache = request.app.state.cache
    snapshot = await cache.get(f"health:{app_id}", "health_snapshot")
    if not snapshot:
        raise HTTPException(404, "No health data available yet")
    return snapshot
