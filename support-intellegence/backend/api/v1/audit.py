"""
Audit log REST endpoints — paginated, integrity-verified.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import select, desc
from sqlalchemy.ext.asyncio import AsyncSession

from api.middleware.auth import get_current_user, require_role, Principal
from core.database import get_db_session
from models.audit import AuditRecordORM, AuditRecordResponse, compute_record_hash

router = APIRouter(prefix="/audit", tags=["audit"])


@router.get("", response_model=list[AuditRecordResponse])
async def list_audit(
    event_type: str | None = Query(None),
    app_id: str | None = Query(None),
    issue_id: str | None = Query(None),
    team_id: str | None = Query(None),
    limit: int = Query(50, le=500),
    offset: int = Query(0, ge=0),
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    stmt = select(AuditRecordORM)
    if event_type:
        stmt = stmt.where(AuditRecordORM.event_type == event_type)
    if app_id:
        stmt = stmt.where(AuditRecordORM.app_id == app_id)
    if issue_id:
        stmt = stmt.where(AuditRecordORM.issue_id == issue_id)
    if team_id:
        stmt = stmt.where(AuditRecordORM.team_id == team_id)
    stmt = stmt.order_by(desc(AuditRecordORM.timestamp)).limit(limit).offset(offset)

    result = await db.execute(stmt)
    records = result.scalars().all()
    return [AuditRecordResponse.from_orm(r) for r in records]


@router.get("/{record_id}", response_model=AuditRecordResponse)
async def get_audit_record(
    record_id: str,
    verify: bool = Query(False, description="Verify SHA-256 integrity hash"),
    principal: Principal = Depends(get_current_user),
    db: AsyncSession = Depends(get_db_session),
):
    result = await db.execute(
        select(AuditRecordORM).where(AuditRecordORM.id == record_id)
    )
    record = result.scalar_one_or_none()
    if not record:
        raise HTTPException(404, f"Audit record {record_id} not found")

    response = AuditRecordResponse.from_orm(record)

    if verify:
        data = {
            "id": record.id,
            "sequence_number": record.sequence_number,
            "event_type": record.event_type,
            "app_id": record.app_id,
            "issue_id": record.issue_id,
            "team_id": record.team_id,
            "actor": record.actor,
            "summary": record.summary,
            "details": record.details,
            "diff_hash": record.diff_hash,
            "previous_hash": record.previous_hash,
            "timestamp": record.timestamp.isoformat() if record.timestamp else "",
        }
        expected = compute_record_hash(data)
        response.integrity_valid = expected == record.record_hash

    return response


@router.get("/export/jsonl")
async def export_audit(
    date: str = Query(..., description="Date in YYYY-MM-DD format"),
    request: Request = None,
    principal: Principal = Depends(require_role("csip:admin")),
):
    """Export a day's audit records as JSONL (compliance download)."""
    import os
    from fastapi.responses import FileResponse
    log_file = f"audit_logs/issues/{date}.jsonl"
    if not os.path.exists(log_file):
        raise HTTPException(404, f"No audit log for {date}")
    return FileResponse(
        log_file,
        media_type="application/x-ndjson",
        filename=f"csip-audit-{date}.jsonl",
    )
