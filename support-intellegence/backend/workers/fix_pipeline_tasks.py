"""
Celery fix-pipeline tasks — async code fix execution and self-improvement.
"""
from __future__ import annotations

import asyncio
import logging

from workers.celery_app import celery_app

logger = logging.getLogger(__name__)


@celery_app.task(name="workers.fix_pipeline_tasks.run_self_improvement", bind=True)
def run_self_improvement(self):
    """Daily self-improvement analysis — shadow mode only."""
    asyncio.run(_async_self_improvement())


async def _async_self_improvement():
    from core.config import get_settings
    from core.database import init_db
    from core.llm_client import ResilientClaudeClient
    from core.llm_budget import LLMBudgetController
    from agents.self_improver import SelfImproverAgent
    from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
    import redis.asyncio as aioredis

    settings = get_settings()

    engine = create_async_engine(settings.database_url, pool_pre_ping=True)
    factory = async_sessionmaker(engine, expire_on_commit=False)
    redis = aioredis.from_url(settings.redis_url, decode_responses=True)

    budget = LLMBudgetController(redis, {
        "self_improver": settings.budget_self_improver,
    })
    claude = ResilientClaudeClient(
        api_key=settings.anthropic_api_key or "sk-mock",
        model=settings.claude_model,
        budget_controller=budget,
    )

    async def noop_broadcast(p): pass

    improver = SelfImproverAgent(
        llm=claude,
        db_session_factory=factory,
        github_token=settings.github_token,
        ws_broadcaster=noop_broadcast,
    )

    proposals = await improver.run_analysis()
    logger.info("Self-improver generated %d proposals", len(proposals))

    await redis.close()
    await engine.dispose()
