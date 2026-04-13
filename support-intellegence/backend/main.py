"""
CSIP Backend — FastAPI application entry point with full async lifespan.
Port: 8092 (avoids conflict with Kafka UI on 8090, Keycloak on 8080)
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import redis.asyncio as aioredis
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from core.config import get_settings
from core.database import init_db, engine
from core.cache import CacheManager
from core.pii_masker import PIIMasker
from core.llm_client import ResilientClaudeClient
from core.llm_budget import LLMBudgetController
from core.vector_store import create_vector_store
from core.leader_election import MonitorLeaderLock
from core.telemetry import setup_telemetry
from core.audit_writer import ImmutableAuditWriter
from agents.orchestrator import Orchestrator
from api.websocket import WebSocketManager
from api.v1 import apps, issues, audit, system
from kafka.consumer import CSIPKafkaConsumer
from kafka.producer import CSIPKafkaProducer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ── 1. Telemetry (first — traces everything after) ────────────────────
    setup_telemetry("csip-backend", settings.otel_endpoint, enabled=settings.otel_enabled)

    # ── 2. Infrastructure connections ─────────────────────────────────────
    redis_client = aioredis.from_url(
        settings.redis_url,
        decode_responses=True,
        max_connections=50,
    )
    await init_db(settings.database_url, settings.database_pool_size)

    from sqlalchemy.ext.asyncio import async_sessionmaker
    from core.database import _write_engine
    session_factory = async_sessionmaker(_write_engine, expire_on_commit=False)

    # ── 3. Core services ──────────────────────────────────────────────────
    pii_masker = PIIMasker()
    vector_store = create_vector_store(
        backend=settings.vector_store_backend,
        path=settings.chromadb_path,
        api_key=settings.pinecone_api_key,
        environment=settings.pinecone_environment,
        index_name=settings.pinecone_index_name,
    )
    budget = LLMBudgetController(
        redis_client,
        budgets={
            "rca_agent":       settings.budget_rca_agent,
            "code_fix_agent":  settings.budget_code_fix_agent,
            "log_analyst":     settings.budget_log_analyst,
            "discovery":       settings.budget_discovery,
            "self_improver":   settings.budget_self_improver,
        },
    )
    claude = ResilientClaudeClient(
        api_key=settings.anthropic_api_key or "sk-placeholder",
        model=settings.claude_model,
        budget_controller=budget,
    )
    leader_lock = MonitorLeaderLock(redis_client, instance_id=str(uuid.uuid4()))
    audit_writer = ImmutableAuditWriter(
        local_path=Path("audit_logs/issues"),
        db_session_factory=session_factory,
        s3_enabled=settings.s3_enabled,
    )

    # ── 4. WebSocket manager ──────────────────────────────────────────────
    ws_manager = WebSocketManager()

    # ── 5. Orchestrator ───────────────────────────────────────────────────
    orchestrator = Orchestrator(
        claude=claude,
        vector_store=vector_store,
        redis=redis_client,
        leader_lock=leader_lock,
        pii_masker=pii_masker,
        session_factory=session_factory,
        audit_writer=audit_writer,
        settings=settings,
    )
    orchestrator.set_ws_broadcaster(ws_manager.broadcast)

    # ── 6. Expose on app.state ────────────────────────────────────────────
    app.state.orchestrator = orchestrator
    app.state.cache = CacheManager(redis_client)
    app.state.budget = budget
    app.state.ws_manager = ws_manager
    app.state.audit = audit_writer

    # ── 7. Kafka ──────────────────────────────────────────────────────────
    kafka_consumer = CSIPKafkaConsumer(settings.kafka_bootstrap_servers, orchestrator)
    kafka_producer = CSIPKafkaProducer(settings.kafka_bootstrap_servers)
    kafka_task = asyncio.create_task(kafka_consumer.start())
    await kafka_producer.start()
    app.state.kafka_producer = kafka_producer

    # ── 8. Resume monitoring for persisted apps ───────────────────────────
    await orchestrator.load_persisted_apps()

    logger.info("CSIP backend ready on port %d", settings.port)

    yield  # ── Application running ────────────────────────────────────────

    # ── Graceful shutdown ─────────────────────────────────────────────────
    logger.info("CSIP shutting down...")
    kafka_task.cancel()
    await kafka_consumer.stop()
    await kafka_producer.stop()
    await orchestrator._monitor.stop_all()
    await redis_client.close()
    if engine:
        await engine.dispose()


# ── Application factory ────────────────────────────────────────────────────────

app = FastAPI(
    title="CSIP — CIBAP Support Intelligence Platform",
    description="Autonomous monitoring, RCA, and code fix pipeline for CIBAP microservices",
    version="2.0.0",
    lifespan=lifespan,
    docs_url="/api/v1/docs" if not settings.is_production else None,
    redoc_url="/api/v1/redoc" if not settings.is_production else None,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3100", "http://localhost:5173", "https://csip.jpmc.internal"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── REST routers ──────────────────────────────────────────────────────────────
app.include_router(apps.router, prefix="/api/v1")
app.include_router(issues.router, prefix="/api/v1")
app.include_router(audit.router, prefix="/api/v1")
app.include_router(system.router, prefix="/api/v1")


# ── WebSocket endpoint ────────────────────────────────────────────────────────

@app.websocket("/api/v1/ws/monitor")
async def websocket_monitor(ws: WebSocket):
    manager: WebSocketManager = ws.app.state.ws_manager
    conn = await manager.connect(ws)
    try:
        await manager.handle_client_messages(conn)
    except WebSocketDisconnect:
        pass
    finally:
        await manager.disconnect(conn)


# ── Global exception handler ─────────────────────────────────────────────────

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error("Unhandled exception: %s", exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error", "type": type(exc).__name__},
    )


# ── Direct run ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=settings.port,
        reload=settings.is_development,
        log_level=settings.log_level.lower(),
    )
