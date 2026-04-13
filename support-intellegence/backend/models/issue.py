"""
Issue ORM model + Pydantic schemas.
Tracks the full lifecycle from detection → RCA → fix → PR → resolution.
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Optional
import uuid

from sqlalchemy import Column, String, Float, Integer, Text, JSON, DateTime, Boolean
from pydantic import BaseModel

from core.database import Base


class IssueCategory(str, Enum):
    INFRASTRUCTURE = "infrastructure"
    CONFIGURATION  = "configuration"
    CODE           = "code"
    UNKNOWN        = "unknown"


class IssueSeverity(str, Enum):
    P0 = "p0"
    P1 = "p1"
    P2 = "p2"
    P3 = "p3"


class IssueStatus(str, Enum):
    OPEN             = "open"
    FAST_CLASSIFIED  = "fast_classified"
    LLM_ANALYZING    = "llm_analyzing"
    FIX_QUEUED       = "fix_queued"
    FIX_BUILDING     = "fix_building"
    FIX_TESTING      = "fix_testing"
    PR_OPEN          = "pr_open"
    PR_MERGED        = "pr_merged"
    DEPLOYED_STAGING = "deployed_staging"
    RESOLVED         = "resolved"
    ESCALATED        = "escalated"
    FALSE_POSITIVE   = "false_positive"


class IssueORM(Base):
    __tablename__ = "issues"

    id                    = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    app_id                = Column(String(36), nullable=False, index=True)
    team_id               = Column(String(100), nullable=False, index=True)
    title                 = Column(String(500), nullable=False)
    category              = Column(String(30), nullable=False, index=True)
    severity              = Column(String(5), nullable=False, index=True)
    confidence            = Column(Float, default=0.0)
    root_cause_summary    = Column(Text)
    technical_detail      = Column(Text)
    classification_method = Column(String(20), default="unknown")  # fast_rule | llm_claude
    error_cluster_id      = Column(String(64))
    error_count           = Column(Integer, default=1)
    first_seen_at         = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    last_seen_at          = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    affected_file         = Column(String(500))
    affected_class        = Column(String(200))
    affected_method       = Column(String(200))
    fix_branch            = Column(String(200))
    fix_pr_url            = Column(String(512))
    fix_pr_number         = Column(Integer)
    fix_attempts          = Column(Integer, default=0)
    fix_attempt_history   = Column(JSON, default=list)
    pagerduty_incident_id = Column(String(100))
    status                = Column(String(30), nullable=False, default="open", index=True)
    final_outcome         = Column(String(30))    # ground truth for self-improver accuracy calc
    llm_tokens_used       = Column(Integer, default=0)
    llm_cost_usd          = Column(Float, default=0.0)
    stack_trace           = Column(Text)
    raw_error             = Column(Text)
    created_at            = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at            = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc),
                                   onupdate=lambda: datetime.now(timezone.utc))
    resolved_at           = Column(DateTime(timezone=True))
    resolution_notes      = Column(Text)


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class IssueResponse(BaseModel):
    id: str
    app_id: str
    team_id: str
    title: str
    category: str
    severity: str
    confidence: float
    root_cause_summary: Optional[str]
    technical_detail: Optional[str]
    classification_method: str
    error_count: int
    affected_file: Optional[str]
    affected_class: Optional[str]
    affected_method: Optional[str]
    fix_branch: Optional[str]
    fix_pr_url: Optional[str]
    fix_pr_number: Optional[int]
    fix_attempts: int
    fix_attempt_history: list
    pagerduty_incident_id: Optional[str]
    status: str
    llm_tokens_used: int
    llm_cost_usd: float
    first_seen_at: str
    last_seen_at: str
    created_at: str
    updated_at: str
    resolved_at: Optional[str]
    resolution_notes: Optional[str]

    @classmethod
    def from_orm(cls, obj: IssueORM) -> "IssueResponse":
        return cls(
            id=obj.id,
            app_id=obj.app_id,
            team_id=obj.team_id,
            title=obj.title,
            category=obj.category,
            severity=obj.severity,
            confidence=obj.confidence or 0.0,
            root_cause_summary=obj.root_cause_summary,
            technical_detail=obj.technical_detail,
            classification_method=obj.classification_method,
            error_count=obj.error_count or 1,
            affected_file=obj.affected_file,
            affected_class=obj.affected_class,
            affected_method=obj.affected_method,
            fix_branch=obj.fix_branch,
            fix_pr_url=obj.fix_pr_url,
            fix_pr_number=obj.fix_pr_number,
            fix_attempts=obj.fix_attempts or 0,
            fix_attempt_history=obj.fix_attempt_history or [],
            pagerduty_incident_id=obj.pagerduty_incident_id,
            status=obj.status,
            llm_tokens_used=obj.llm_tokens_used or 0,
            llm_cost_usd=obj.llm_cost_usd or 0.0,
            first_seen_at=obj.first_seen_at.isoformat() if obj.first_seen_at else "",
            last_seen_at=obj.last_seen_at.isoformat() if obj.last_seen_at else "",
            created_at=obj.created_at.isoformat() if obj.created_at else "",
            updated_at=obj.updated_at.isoformat() if obj.updated_at else "",
            resolved_at=obj.resolved_at.isoformat() if obj.resolved_at else None,
            resolution_notes=obj.resolution_notes,
        )


class IssueResolveRequest(BaseModel):
    notes: Optional[str] = None
    final_outcome: Optional[str] = None


class IssueEscalateRequest(BaseModel):
    reason: str
