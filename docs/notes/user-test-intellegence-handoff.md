# CTIP Handoff

## Current state

The local CTIP developer edition in this workspace is in strong shape and verified.

Implemented:
- FastAPI backend with SQLAlchemy-backed persistence
- SQLite default plus PostgreSQL support via `DATABASE_URL`
- Local auth/RBAC with three built-in users:
  - `olivia.viewer`
  - `quinn.qa`
  - `avery.admin`
- Role-aware frontend with user switching
- Suite execution flow with local Node runner in `playwright-runner/run-shard.mjs`
- Execution-event audit logging
- Evidence generation under `backend/data/evidence/`
- Celery/Redis orchestration support with eager fallback
- Local search/index analytics across suite runs, execution events, and evidence
- Elasticsearch-compatible search backend with local fallback
- Docker Compose stack with PostgreSQL, Redis, Elasticsearch, backend, worker, and frontend
- Kubernetes and monitoring scaffolds

## Important files

Backend core:
- `backend/main.py`
- `backend/core/config.py`
- `backend/core/database.py`
- `backend/core/db_models.py`
- `backend/core/repositories.py`
- `backend/core/evidence_generator.py`
- `backend/core/auth.py`

Backend services:
- `backend/services/execution.py`
- `backend/services/analysis.py`
- `backend/services/search_index.py`
- `backend/services/search_backend.py`
- `backend/services/dashboard.py`
- `backend/services/compliance.py`

Backend routes:
- `backend/api/v1/auth.py`
- `backend/api/v1/runs.py`
- `backend/api/v1/test_cases.py`
- `backend/api/v1/evidence.py`
- `backend/api/v1/search.py`
- `backend/api/v1/compliance.py`
- `backend/api/v1/analysis.py`

Workers / runner:
- `backend/workers/celery_app.py`
- `backend/workers/execution_tasks.py`
- `playwright-runner/run-shard.mjs`

Frontend:
- `frontend/src/App.tsx`
- `frontend/src/styles.css`

Infra / docs:
- `docker-compose.yml`
- `README.md`
- `backend/.env.example`

## Verified before handoff

Passed:
- `cd backend && python -m pytest tests`
- `cd frontend && npm run build`
- `cd playwright-runner && node run-shard.mjs`
- `cd backend && python -c "import celery, redis; print(celery.__version__)"`
- `cd backend && python -c "import elasticsearch; print(elasticsearch.__versionstr__)"`

Latest backend test count:
- 16 passing tests

## Current behavior notes

- Search backend is configurable.
  - `SEARCH_BACKEND_MODE=auto` uses Elasticsearch when reachable, otherwise falls back to local in-process indexing.
  - `SEARCH_BACKEND_MODE=local` forces local fallback.
- Queue behavior is configurable.
  - `TASK_QUEUE_MODE=eager` runs inline, good for local dev and tests.
  - `TASK_QUEUE_MODE=celery` routes execution through Redis/Celery.
- Compliance and self-improvement APIs are admin-only.
- Evidence access and suite-trigger actions require QA or admin.

## Most valuable next steps

Priority order I would take next:
1. Replace local auth with more realistic JWT/session flow and optional SSO-style structure.
2. Add real Elasticsearch document mappings/index lifecycle and richer filtering/sorting instead of current hybrid local sync model.
3. Add Kafka producer/consumer integration for deploy events and result publication.
4. Add Pinecone/vector search integration for semantic test discovery and dedup.
5. Move from local runner simulation toward actual Playwright/browser-based tests.
6. Add Alembic migrations so schema evolution is cleaner than `create_all`.

## Recommendation for the very next session

Start with item 6 plus 2 together if time allows:
- introduce Alembic migrations for the current SQLAlchemy schema
- then deepen the Elasticsearch path so the app uses true indexed analytics more often and less local fallback

## Run commands

Backend:
```powershell
cd backend
python -m pip install -r requirements.txt
uvicorn main:app --reload --port 8091
```

Frontend:
```powershell
cd frontend
npm install
npm run dev
```

Full stack:
```powershell
docker compose up --build
```
