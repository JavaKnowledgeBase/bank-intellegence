"""
Orchestrator — coordinates all agents and wires them together.
Entry point for both Kafka-pushed events and HTTP-triggered analysis.
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy import select, update

from agents.monitor_agent import MonitorAgent
from agents.log_analyst import LogAnalyst
from agents.rca_agent import RCAAgent
from agents.code_fix_agent import CodeFixAgent
from agents.self_improver import SelfImproverAgent
from core.cache import CacheManager
from core.audit_writer import ImmutableAuditWriter
from core.llm_client import ResilientClaudeClient
from core.vector_store import VectorStore
from core.leader_election import MonitorLeaderLock
from core.pii_masker import PIIMasker
from models.app_config import AppConfigORM
from models.issue import IssueORM, IssueStatus, IssueCategory
from models.audit import AuditEventType

logger = logging.getLogger(__name__)


class Orchestrator:
    """Central coordinator — receives events and drives the full pipeline."""

    def __init__(
        self,
        claude: ResilientClaudeClient,
        vector_store: VectorStore,
        redis,
        leader_lock: MonitorLeaderLock,
        pii_masker: PIIMasker,
        session_factory: async_sessionmaker,
        audit_writer: ImmutableAuditWriter,
        settings,
    ):
        self._cache = CacheManager(redis)
        self._vs = vector_store
        self._pii = pii_masker
        self._session_factory = session_factory
        self._audit = audit_writer
        self._settings = settings

        # WebSocket broadcaster (set externally before use)
        self._ws_broadcast = self._noop_broadcast

        self._log_analyst = LogAnalyst(
            cache=self._cache,
            vector_store=vector_store,
            pii_masker=pii_masker,
            dedup_window=settings.dedup_window_seconds,
            max_patterns_per_window=settings.max_error_patterns_per_window,
            semantic_threshold=settings.semantic_dedup_threshold,
        )
        self._rca = RCAAgent(llm=claude, vector_store=vector_store)
        self._fix = CodeFixAgent(
            llm=claude,
            github_token=settings.github_token,
            ws_broadcaster=self._ws_broadcast,
            max_attempts=settings.fix_max_attempts,
        )
        self._monitor = MonitorAgent(
            cache=self._cache,
            leader_lock=leader_lock,
            ws_broadcaster=self._ws_broadcast,
            issue_callback=self._on_issue_detected,
            session_factory=session_factory,
        )
        self._improver = SelfImproverAgent(
            llm=claude,
            db_session_factory=session_factory,
            github_token=settings.github_token,
            ws_broadcaster=self._ws_broadcast,
        )

    def set_ws_broadcaster(self, fn) -> None:
        self._ws_broadcast = fn
        self._monitor._broadcast = fn
        self._fix._broadcast = fn
        self._improver._broadcast = fn

    async def load_persisted_apps(self) -> None:
        """On startup, resume monitoring for all previously registered apps."""
        async with self._session_factory() as session:
            result = await session.execute(
                select(AppConfigORM).where(AppConfigORM.status != "paused")
            )
            apps = result.scalars().all()

        for app in apps:
            await self._monitor.start_monitoring(app)

        logger.info("Resumed monitoring for %d apps", len(apps))

        await self._audit.write(
            event_type=AuditEventType.SYSTEM_STARTUP,
            actor="csip:system",
            summary=f"CSIP started — monitoring {len(apps)} apps",
            details={"app_count": len(apps)},
        )

    # ── Kafka event handlers ──────────────────────────────────────────────────

    async def handle_error_event(self, event: dict) -> None:
        """Called by Kafka error processor for each error event."""
        events = [event] if isinstance(event, dict) else event
        clusters = await self._log_analyst.process_batch(events)

        for cluster in clusters:
            if cluster.count < self._settings.min_error_count_for_rca:
                continue
            app = await self._get_app_by_service(cluster.service)
            if app:
                asyncio.create_task(self._run_rca_pipeline(app, cluster))

    async def handle_health_event(self, event: dict) -> None:
        await self._monitor.process_kafka_health_event(event)

    async def handle_deployment_event(self, event: dict) -> None:
        service = event.get("service", "unknown")
        cache_key = f"csip:deployments:{service}"
        deployments = await self._cache.get(cache_key, "app_config") or []
        deployments.insert(0, event)
        deployments = deployments[:10]  # Keep last 10
        await self._cache.set(cache_key, deployments, "app_config")

    # ── App management ────────────────────────────────────────────────────────

    async def register_app(self, config: dict, created_by: str) -> AppConfigORM:
        """Discover app metadata from its health endpoint and register."""
        app = await self._discover_app(config)
        async with self._session_factory() as session:
            session.add(app)
            await session.commit()
            await session.refresh(app)

        await self._monitor.start_monitoring(app)
        await self._audit.write(
            event_type=AuditEventType.APP_REGISTERED,
            actor=f"user:{created_by}",
            summary=f"Registered app: {app.name}",
            details={"app_id": app.id, "base_url": app.base_url, "tier": app.tier},
            app_id=app.id,
        )
        await self._cache.invalidate_prefix("app_config")
        return app

    async def _discover_app(self, config: dict) -> AppConfigORM:
        """Auto-populate AppConfig from the service's actuator endpoints."""
        import httpx, re
        base_url = config["url"].rstrip("/")
        name = "unknown-service"

        try:
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.get(f"{base_url}/actuator/info")
                if resp.status_code == 200:
                    info = resp.json()
                    name = (
                        info.get("app", {}).get("name")
                        or info.get("build", {}).get("artifact")
                        or re.sub(r"https?://|:\d+", "", base_url).strip("/").split("/")[-1]
                    )
        except Exception:
            name = re.sub(r"https?://|:\d+", "", base_url).strip("/").split("/")[-1] or "unknown"

        return AppConfigORM(
            id=str(uuid.uuid4()),
            name=name,
            team_id=config.get("team_id", "default"),
            namespace=config.get("namespace", "default"),
            tier=config.get("tier", "p2"),
            base_url=base_url,
            repo_url=config.get("repo_url"),
            codeowners=config.get("codeowners", []),
            description=config.get("description"),
            created_by=config.get("created_by", "system"),
            status="unknown",
        )

    # ── RCA + Fix pipeline ────────────────────────────────────────────────────

    async def _run_rca_pipeline(self, app: AppConfigORM, cluster) -> None:
        """Full pipeline: log cluster → RCA → (optional) code fix."""
        issue_id = str(uuid.uuid4())

        # Create issue record
        issue = IssueORM(
            id=issue_id,
            app_id=app.id,
            team_id=app.team_id,
            title=f"[{cluster.service}] {cluster.error_class}: {cluster.message[:100]}",
            category=IssueCategory.UNKNOWN,
            severity="p2",
            confidence=0.0,
            error_cluster_id=cluster.cluster_id,
            error_count=cluster.count,
            stack_trace=cluster.stack_trace,
            raw_error=cluster.message,
            status=IssueStatus.OPEN,
        )

        async with self._session_factory() as session:
            session.add(issue)
            await session.commit()

        await self._broadcast_issue(issue, "issue_opened")

        # Gather deployment context
        deployments = await self._cache.get(f"csip:deployments:{cluster.service}", "app_config") or []

        # Run RCA
        async with self._session_factory() as session:
            await session.execute(
                update(IssueORM).where(IssueORM.id == issue_id)
                .values(status=IssueStatus.LLM_ANALYZING)
            )
            await session.commit()

        rca = await self._rca.classify(
            service=cluster.service,
            error_message=cluster.message,
            stack_trace=cluster.stack_trace,
            recent_deployments=deployments,
        )

        # Update issue with RCA result
        async with self._session_factory() as session:
            await session.execute(
                update(IssueORM).where(IssueORM.id == issue_id).values(
                    title=rca.title,
                    category=rca.category,
                    severity=rca.severity,
                    confidence=rca.confidence,
                    root_cause_summary=rca.root_cause_summary,
                    technical_detail=rca.technical_detail,
                    affected_file=rca.affected_file,
                    affected_class=rca.affected_class,
                    affected_method=rca.affected_method,
                    classification_method=rca.classification_method,
                    llm_tokens_used=rca.llm_tokens_used,
                    llm_cost_usd=rca.llm_cost_usd,
                    status=IssueStatus.FAST_CLASSIFIED if rca.classification_method == "fast_rule"
                           else IssueStatus.FIX_QUEUED if (rca.category == "code" and rca.confidence >= 0.85)
                           else IssueStatus.ESCALATED,
                )
            )
            await session.commit()

        await self._audit.write(
            event_type=AuditEventType.ISSUE_CLASSIFIED,
            actor=f"csip:agent:{rca.classification_method}",
            summary=f"Issue classified: {rca.category}/{rca.severity} (conf={rca.confidence:.2f})",
            details={"rca": rca.__dict__},
            app_id=app.id,
            issue_id=issue_id,
            team_id=app.team_id,
        )

        # Queue code fix if appropriate
        if rca.category == "code" and rca.confidence >= self._settings.rca_confidence_threshold:
            await self._run_fix_pipeline(app, issue_id, rca)

    async def _run_fix_pipeline(self, app, issue_id: str, rca) -> None:
        result = await self._fix.run_pipeline(
            issue_id=issue_id, app=app, rca_result=rca
        )

        new_status = {
            "pr_open": IssueStatus.PR_OPEN,
            "escalated": IssueStatus.ESCALATED,
            "failed": IssueStatus.ESCALATED,
        }.get(result.final_status, IssueStatus.ESCALATED)

        async with self._session_factory() as session:
            await session.execute(
                update(IssueORM).where(IssueORM.id == issue_id).values(
                    status=new_status,
                    fix_pr_url=result.pr_url,
                    fix_pr_number=result.pr_number,
                    fix_attempts=len(result.attempts),
                    fix_attempt_history=[a.__dict__ for a in result.attempts],
                )
            )
            await session.commit()

        await self._audit.write(
            event_type=AuditEventType.FIX_PR_CREATED if result.pr_url else AuditEventType.FIX_FAILED,
            actor="csip:agent:code_fix",
            summary=f"Fix pipeline {result.final_status}: {result.pr_url or result.escalation_reason}",
            details={"result": result.__dict__},
            app_id=app.id,
            issue_id=issue_id,
        )

    # ── Helpers ───────────────────────────────────────────────────────────────

    async def _get_app_by_service(self, service_name: str) -> Optional[AppConfigORM]:
        async with self._session_factory() as session:
            result = await session.execute(
                select(AppConfigORM).where(AppConfigORM.name == service_name)
            )
            return result.scalar_one_or_none()

    async def _broadcast_issue(self, issue: IssueORM, event_type: str) -> None:
        await self._ws_broadcast({
            "type": event_type,
            "issue": {
                "id": issue.id,
                "app_id": issue.app_id,
                "title": issue.title,
                "category": issue.category,
                "severity": issue.severity,
                "status": issue.status,
            },
        })

    async def _noop_broadcast(self, payload: dict) -> None:
        pass  # replaced by real broadcaster once WebSocket manager is ready

    async def _on_issue_detected(self, app, cluster) -> None:
        asyncio.create_task(self._run_rca_pipeline(app, cluster))

    async def run_self_improvement(self) -> None:
        await self._improver.run_analysis()
