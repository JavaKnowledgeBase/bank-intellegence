"""
AppConfig ORM model + Pydantic schemas.
"""
from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Optional
import uuid

from sqlalchemy import Column, String, Boolean, Integer, Text, JSON, DateTime
from sqlalchemy.dialects.postgresql import UUID
from pydantic import BaseModel, HttpUrl, field_validator

from core.database import Base


class AppStatus(str, Enum):
    HEALTHY  = "healthy"
    DEGRADED = "degraded"
    DOWN     = "down"
    UNKNOWN  = "unknown"
    PAUSED   = "paused"


class AppTier(str, Enum):
    P0 = "p0"   # Payment-critical
    P1 = "p1"   # Customer-facing
    P2 = "p2"   # Internal
    P3 = "p3"   # Non-critical


class AppConfigORM(Base):
    __tablename__ = "app_configs"

    id                    = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    name                  = Column(String(200), nullable=False)
    team_id               = Column(String(100), nullable=False, index=True)
    namespace             = Column(String(100), nullable=False)
    tier                  = Column(String(10), nullable=False, default="p2")
    base_url              = Column(String(512), nullable=False)
    health_path           = Column(String(200), default="/actuator/health")
    metrics_path          = Column(String(200), default="/actuator/prometheus")
    kafka_topics_produced = Column(JSON, default=list)
    k8s_deployment_name   = Column(String(200))
    k8s_namespace         = Column(String(100))
    repo_url              = Column(String(512))
    repo_branch           = Column(String(100), default="main")
    codeowners            = Column(JSON, default=list)
    tech_stack            = Column(String(100), default="java-spring-boot")
    description           = Column(Text)
    polling_interval_secs = Column(Integer, default=60)
    smoke_test_endpoint   = Column(String(512))
    fix_auto_pr           = Column(Boolean, default=True)
    fix_require_human     = Column(Boolean, default=True)
    monitoring_paused_until = Column(String(50))
    status                = Column(String(20), default="unknown", index=True)
    created_by            = Column(String(100))
    created_at            = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at            = Column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc),
                                   onupdate=lambda: datetime.now(timezone.utc))


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class AppConfigCreate(BaseModel):
    url: str                    # Discovery: provide base URL; CSIP auto-fills the rest
    description: Optional[str] = None
    team_id: str = "default"
    tier: AppTier = AppTier.P2
    repo_url: Optional[str] = None
    codeowners: list[str] = []

    @field_validator("url")
    @classmethod
    def url_must_have_scheme(cls, v: str) -> str:
        if not v.startswith(("http://", "https://")):
            raise ValueError("url must start with http:// or https://")
        return v.rstrip("/")


class AppConfigUpdate(BaseModel):
    tier: Optional[AppTier] = None
    repo_url: Optional[str] = None
    codeowners: Optional[list[str]] = None
    polling_interval_secs: Optional[int] = None
    fix_auto_pr: Optional[bool] = None
    description: Optional[str] = None


class AppConfigResponse(BaseModel):
    id: str
    name: str
    team_id: str
    namespace: str
    tier: str
    base_url: str
    health_path: str
    metrics_path: Optional[str]
    kafka_topics_produced: list[str]
    repo_url: Optional[str]
    repo_branch: str
    codeowners: list[str]
    tech_stack: str
    description: Optional[str]
    polling_interval_secs: int
    fix_auto_pr: bool
    fix_require_human: bool
    status: str
    created_by: Optional[str]
    created_at: str
    updated_at: str

    @classmethod
    def from_orm(cls, obj: AppConfigORM) -> "AppConfigResponse":
        return cls(
            id=obj.id,
            name=obj.name,
            team_id=obj.team_id,
            namespace=obj.namespace or "default",
            tier=obj.tier,
            base_url=obj.base_url,
            health_path=obj.health_path,
            metrics_path=obj.metrics_path,
            kafka_topics_produced=obj.kafka_topics_produced or [],
            repo_url=obj.repo_url,
            repo_branch=obj.repo_branch,
            codeowners=obj.codeowners or [],
            tech_stack=obj.tech_stack,
            description=obj.description,
            polling_interval_secs=obj.polling_interval_secs,
            fix_auto_pr=obj.fix_auto_pr,
            fix_require_human=obj.fix_require_human,
            status=obj.status,
            created_by=obj.created_by,
            created_at=obj.created_at.isoformat() if obj.created_at else "",
            updated_at=obj.updated_at.isoformat() if obj.updated_at else "",
        )
