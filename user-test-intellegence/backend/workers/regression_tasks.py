from workers.celery_app import celery_app


@celery_app.task(name="ctip.regression.run")
def regression_task() -> dict:
    return {"task": "regression", "status": "stubbed"}
