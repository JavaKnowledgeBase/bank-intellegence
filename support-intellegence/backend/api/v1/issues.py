"""
Issue management REST endpoints.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from api.middleware.auth import get_current_user, require_role, Principal
from core.database import get_db_session
from models.issue import IssueORM, IssueResponse, IssueStatus, IssueResolveRequest, IssueEscalateRequest

router = APIRouter(prefix="/issues", tags=["issues"])
logger = logging.getLogger(__name__)


@router.get("", response_model=list[IssueResponse])
async def list_issues(
    status: Optional[str] = Query(None),
    category: Optional[str] = Query(None),
    severity: Optional[str] = Query(None),
    team_id: Optional[str] = Query(None),
    app_id: Optional[str] = Query(None),
    limit: int = Query(50, le=500),
    offset: int = Query(0, ge=0),
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    stmt = select(IssueORM)
    if status:
        stmt = stmt.where(IssueORM.status == status)
    if category:
        stmt = stmt.where(IssueORM.category == category)
    if severity:
        stmt = stmt.where(IssueORM.severity == severity)
    if team_id:
        stmt = stmt.where(IssueORM.team_id == team_id)
    if app_id:
        stmt = stmt.where(IssueORM.app_id == app_id)

    stmt = stmt.order_by(desc(IssueORM.created_at)).limit(limit).offset(offset)
    result = await db.execute(stmt)
    issues = result.scalars().all()
    return [IssueResponse.from_orm(i) for i in issues]


@router.get("/{issue_id}", response_model=IssueResponse)
async def get_issue(
    issue_id: str,
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(IssueORM).where(IssueORM.id == issue_id))
    issue = result.scalar_one_or_none()
    if not issue:
        raise HTTPException(404, f"Issue {issue_id} not found")
    return IssueResponse.from_orm(issue)


@router.patch("/{issue_id}/resolve")
async def resolve_issue(
    issue_id: str,
    body: IssueResolveRequest,
    principal: Principal = Depends(require_role("csip:operator")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(IssueORM).where(IssueORM.id == issue_id))
    issue = result.scalar_one_or_none()
    if not issue:
        raise HTTPException(404, f"Issue {issue_id} not found")

    issue.status = IssueStatus.RESOLVED
    issue.resolved_at = datetime.now(timezone.utc)
    issue.resolution_notes = body.notes
    if body.final_outcome:
        issue.final_outcome = body.final_outcome
    await db.commit()
    return {"message": "Issue resolved", "issue_id": issue_id}


@router.post("/{issue_id}/escalate")
async def escalate_issue(
    issue_id: str,
    body: IssueEscalateRequest,
    principal: Principal = Depends(require_role("csip:operator")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(IssueORM).where(IssueORM.id == issue_id))
    issue = result.scalar_one_or_none()
    if not issue:
        raise HTTPException(404, f"Issue {issue_id} not found")

    issue.status = IssueStatus.ESCALATED
    issue.resolution_notes = f"Escalated: {body.reason}"
    await db.commit()
    return {"message": "Issue escalated", "issue_id": issue_id}


@router.post("/{issue_id}/false-positive")
async def mark_false_positive(
    issue_id: str,
    principal: Principal = Depends(require_role("csip:operator")),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(select(IssueORM).where(IssueORM.id == issue_id))
    issue = result.scalar_one_or_none()
    if not issue:
        raise HTTPException(404, f"Issue {issue_id} not found")

    issue.status = IssueStatus.FALSE_POSITIVE
    issue.final_outcome = "false_positive"
    issue.resolved_at = datetime.now(timezone.utc)
    await db.commit()
    return {"message": "Marked as false positive", "issue_id": issue_id}
