import asyncio

from workers.celery_app import celery_app


@celery_app.task(name="ctip.execution.run_suite")
def run_suite_execution_task(suite_run_id: str, service: str) -> dict:
    from services.execution import ExecutionService

    asyncio.run(ExecutionService().run_queued_suite(suite_run_id, service))
    return {"suite_run_id": suite_run_id, "service": service, "status": "queued-executed"}
