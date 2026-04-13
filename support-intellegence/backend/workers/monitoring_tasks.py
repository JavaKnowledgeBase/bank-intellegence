"""
Celery monitoring tasks — periodic health check sweep.
"""
from __future__ import annotations

import asyncio
import logging

from workers.celery_app import celery_app

logger = logging.getLogger(__name__)


@celery_app.task(name="workers.monitoring_tasks.bulk_health_check", bind=True)
def bulk_health_check(self):
    """Trigger health probes for all registered apps (fallback for non-Kafka apps)."""
    asyncio.run(_async_bulk_health_check())


async def _async_bulk_health_check():
    from core.config import get_settings
    from core.database import init_db
    from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
    from sqlalchemy import select
    from models.app_config import AppConfigORM
    import httpx

    settings = get_settings()
    engine = create_async_engine(settings.database_url, pool_pre_ping=True)
    factory = async_sessionmaker(engine, expire_on_commit=False)

    async with factory() as session:
        result = await session.execute(
            select(AppConfigORM).where(AppConfigORM.status != "paused")
        )
        apps = result.scalars().all()

    async with httpx.AsyncClient(timeout=8.0) as client:
        tasks = [_probe_one(app, client) for app in apps]
        results = await asyncio.gather(*tasks, return_exceptions=True)

    success = sum(1 for r in results if not isinstance(r, Exception))
    logger.info("Bulk health check: %d/%d apps probed", success, len(apps))
    await engine.dispose()


async def _probe_one(app, client) -> dict:
    import time
    url = f"{app.base_url}{app.health_path}"
    t0 = time.monotonic()
    try:
        resp = await client.get(url)
        ms = int((time.monotonic() - t0) * 1000)
        status = "healthy" if resp.status_code == 200 else "degraded"
    except Exception:
        status = "down"
        ms = 0
    return {"app_id": app.id, "status": status, "response_ms": ms}
