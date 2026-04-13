"""
Celery analysis tasks — queue-based RCA triggering.
"""
from __future__ import annotations

import asyncio
import logging

from workers.celery_app import celery_app

logger = logging.getLogger(__name__)


@celery_app.task(name="workers.analysis_tasks.trigger_rca", bind=True)
def trigger_rca(self, app_id: str, error_cluster_data: dict):
    """Queue an RCA analysis for a given error cluster."""
    asyncio.run(_async_trigger_rca(app_id, error_cluster_data))


async def _async_trigger_rca(app_id: str, error_cluster_data: dict):
    logger.info("Celery RCA triggered for app %s cluster %s",
                app_id, error_cluster_data.get("cluster_id"))
    # In a full deployment this would call the orchestrator directly
    # via a shared broker message. For now, log the trigger.
