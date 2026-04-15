from workers.celery_app import celery_app


@celery_app.task(name="ctip.scout.run")
def scout_task() -> dict:
    return {"task": "scout", "status": "stubbed"}
