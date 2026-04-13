"""
Celery application factory.
Queues: monitoring, analysis, fix-pipeline (separate concurrency pools).
"""
from __future__ import annotations

from celery import Celery
from celery.schedules import crontab

from core.config import get_settings

settings = get_settings()

celery_app = Celery(
    "csip",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=[
        "workers.monitoring_tasks",
        "workers.analysis_tasks",
        "workers.fix_pipeline_tasks",
    ],
)

celery_app.conf.update(
    # Serialization
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    # Routing — separate queues per functional area
    task_routes={
        "workers.monitoring_tasks.*": {"queue": "monitoring"},
        "workers.analysis_tasks.*":   {"queue": "analysis"},
        "workers.fix_pipeline_tasks.*": {"queue": "fix-pipeline"},
    },
    task_default_queue="monitoring",
    # Reliability
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    # Timeouts
    task_soft_time_limit=300,   # 5 min soft
    task_time_limit=600,        # 10 min hard kill
    # Result retention
    result_expires=3600 * 24,
    # Beat schedule
    beat_schedule={
        "health-check-all-apps": {
            "task": "workers.monitoring_tasks.bulk_health_check",
            "schedule": 60.0,  # every 60 seconds
        },
        "self-improvement-daily": {
            "task": "workers.fix_pipeline_tasks.run_self_improvement",
            "schedule": crontab(hour=1, minute=0),  # 1 AM UTC
        },
    },
)
