from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.internal.shard_result import router as shard_result_router
from api.v1.analysis import router as analysis_router
from api.v1.auth import router as auth_router
from api.v1.compliance import router as compliance_router
from api.v1.coverage import router as coverage_router
from api.v1.dashboard import router as dashboard_router
from api.v1.evidence import router as evidence_router
from api.v1.gate import router as gate_router
from api.v1.runs import router as runs_router
from api.v1.scout import router as scout_router
from api.v1.knowledge import router as knowledge_router
from api.v1.search import router as search_router
from api.v1.self_improvement import router as self_improvement_router
from api.v1.test_cases import router as test_cases_router
from api.v1.webhooks import router as webhook_router
from api.websocket import router as websocket_router
from core.config import settings
from core.migrations import ensure_database_schema
from core.seed_data import seed_database


@asynccontextmanager
async def lifespan(_: FastAPI):
    ensure_database_schema()
    seed_database()
    yield


def create_app() -> FastAPI:
    app = FastAPI(title=settings.app_name, version="0.5.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.frontend_origin, "http://localhost:5173", "http://localhost:3200"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/api/v1/health")
    def health() -> dict:
        return {"status": "ok", "app": settings.app_name, "environment": settings.environment, "version": "0.5.0"}

    app.include_router(auth_router)
    app.include_router(test_cases_router)
    app.include_router(runs_router)
    app.include_router(gate_router)
    app.include_router(coverage_router)
    app.include_router(scout_router)
    app.include_router(self_improvement_router)
    app.include_router(webhook_router)
    app.include_router(shard_result_router)
    app.include_router(dashboard_router)
    app.include_router(compliance_router)
    app.include_router(analysis_router)
    app.include_router(evidence_router)
    app.include_router(knowledge_router)
    app.include_router(search_router)
    app.include_router(websocket_router)
    return app


app = create_app()
