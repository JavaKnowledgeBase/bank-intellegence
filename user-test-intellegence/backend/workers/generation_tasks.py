from workers.celery_app import celery_app


@celery_app.task(name="ctip.generation.generate_tests")
def generate_tests_task() -> dict:
    return {"task": "generation", "status": "stubbed"}
