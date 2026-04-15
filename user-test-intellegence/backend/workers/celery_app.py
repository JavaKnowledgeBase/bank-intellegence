from celery import Celery

from core.config import settings


celery_app = Celery(
    "ctip-local",
    broker=settings.resolved_celery_broker_url,
    backend=settings.resolved_celery_result_backend,
)

celery_app.conf.update(
    task_always_eager=settings.task_queue_mode == "eager",
    task_eager_propagates=True,
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    timezone="UTC",
    enable_utc=True,
)
