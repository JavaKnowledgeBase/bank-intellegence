"""
Immutable audit record — append-only with SHA-256 integrity chain.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional
import uuid
import hashlib
import json

from sqlalchemy import Column, String, Integer, Text, JSON, DateTime, Boolean
from pydantic import BaseModel

from core.database import Base


class AuditRecordORM(Base):
    __tablename__ = "audit_records"

    id              = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    sequence_number = Column(Integer, nullable=False)
    event_type      = Column(String(100), nullable=False, index=True)
    app_id          = Column(String(36), index=True)
    issue_id        = Column(String(36), index=True)
    team_id         = Column(String(100), index=True)
    actor           = Column(String(200), nullable=False)   # csip:agent:rca | user:john
    actor_ip        = Column(String(50))
    summary         = Column(String(1000), nullable=False)
    details         = Column(JSON, nullable=False, default=dict)
    diff            = Column(Text)                          # git unified diff
    diff_hash       = Column(String(64))
    previous_hash   = Column(String(64), default="GENESIS")
    record_hash     = Column(String(64), nullable=False)
    timestamp       = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True)


def compute_record_hash(record_data: dict) -> str:
    """Deterministic SHA-256 over sorted JSON representation."""
    canonical = json.dumps(record_data, sort_keys=True, default=str)
    return hashlib.sha256(canonical.encode()).hexdigest()


class AuditEventType(str):
    APP_REGISTERED    = "app.registered"
    APP_UPDATED       = "app.updated"
    APP_REMOVED       = "app.removed"
    APP_PAUSED        = "app.paused"
    ISSUE_DETECTED    = "issue.detected"
    ISSUE_CLASSIFIED  = "issue.classified"
    ISSUE_ESCALATED   = "issue.escalated"
    ISSUE_RESOLVED    = "issue.resolved"
    ISSUE_FALSE_POS   = "issue.false_positive"
    FIX_STARTED       = "fix.started"
    FIX_DIFF_GENERATED = "fix.diff_generated"
    FIX_BUILD_PASSED  = "fix.build_passed"
    FIX_BUILD_FAILED  = "fix.build_failed"
    FIX_PR_CREATED    = "fix.pr_created"
    FIX_PR_MERGED     = "fix.pr_merged"
    FIX_FAILED        = "fix.failed"
    IMPROVEMENT_PROPOSED = "improvement.proposed"
    SYSTEM_STARTUP    = "system.startup"


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class AuditRecordResponse(BaseModel):
    id: str
    sequence_number: int
    event_type: str
    app_id: Optional[str]
    issue_id: Optional[str]
    team_id: Optional[str]
    actor: str
    summary: str
    details: dict
    diff: Optional[str]
    diff_hash: Optional[str]
    previous_hash: str
    record_hash: str
    timestamp: str
    integrity_valid: Optional[bool] = None

    @classmethod
    def from_orm(cls, obj: AuditRecordORM) -> "AuditRecordResponse":
        return cls(
            id=obj.id,
            sequence_number=obj.sequence_number,
            event_type=obj.event_type,
            app_id=obj.app_id,
            issue_id=obj.issue_id,
            team_id=obj.team_id,
            actor=obj.actor,
            summary=obj.summary,
            details=obj.details or {},
            diff=obj.diff,
            diff_hash=obj.diff_hash,
            previous_hash=obj.previous_hash,
            record_hash=obj.record_hash,
            timestamp=obj.timestamp.isoformat() if obj.timestamp else "",
        )
