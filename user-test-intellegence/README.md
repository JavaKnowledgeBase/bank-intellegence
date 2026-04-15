# CTIP Local App

This workspace contains a runnable local developer edition of the CIBAP Test Intelligence Platform described in `TESTING_EXPERT_GUIDE.md`.

## Included now

- `backend/`: FastAPI API with SQLAlchemy-backed persistence, seeded data, local auth and RBAC, compliance reporting, dashboard summary, webhook intake, websocket suite updates, evidence generation, execution-event audit logging, analysis endpoints, Elasticsearch-compatible indexing/search, and Celery-backed orchestration support
- `frontend/`: React + TypeScript dashboard with local user switching, role-aware access, deployment gate, compliance snapshot, governed test creation, service footprint, failure analysis, evidence vault, execution timeline, coverage, scout activity, self-improvement queue, and search/index analytics views
- `playwright-runner/`: working local Node runner script used by the backend for shard execution in local mode
- `k8s/`: Kubernetes deployment, execution namespace, quota, HPA, PDB, and network policy scaffolds
- `monitoring/`: Grafana and alerting placeholders
- `docker-compose.yml`: local full-stack bring-up option with PostgreSQL, Redis, Elasticsearch, backend, worker, and frontend

## Local users

- `olivia.viewer`: read-only dashboard access
- `quinn.qa`: can create tests, trigger suite runs, and view evidence
- `avery.admin`: full access including compliance and self-improvement views

Tokens are issued through `/api/v1/auth/login` and used as bearer tokens for protected routes.

## Database, queue, and search

The backend uses SQLAlchemy and accepts `DATABASE_URL`.

Defaults:
- local direct run: sqlite via `sqlite+pysqlite:///./data/ctip.db`
- Docker Compose: PostgreSQL via `postgresql+psycopg://ctip:ctip@postgres:5432/ctip`

Queue behavior:
- `TASK_QUEUE_MODE=eager`: execute queued tasks inline, good for direct local development and tests
- `TASK_QUEUE_MODE=celery`: publish suite execution through Redis to the Celery worker

Search behavior:
- `SEARCH_BACKEND_MODE=auto`: use Elasticsearch when reachable, otherwise local fallback
- `SEARCH_BACKEND_MODE=local`: always use the in-process local index
- optional Elasticsearch endpoint via `ELASTICSEARCH_URL`
- `/api/v1/search/sync` refreshes the searchable document set

## Key local API surfaces

- `/api/v1/auth/login`
- `/api/v1/auth/me`
- `/api/v1/dashboard/summary`
- `/api/v1/compliance/report?month=YYYY-MM&service=all`
- `/api/v1/analysis/failures`
- `/api/v1/analysis/suggestions/{service}`
- `/api/v1/analysis/scout-stats`
- `/api/v1/analysis/execution-events`
- `/api/v1/evidence`
- `/api/v1/search`
- `/api/v1/search/analytics`
- `/api/v1/search/sync`

## Local execution behavior

When `EXECUTION_MODE=local`, suite runs use the Node runner in `playwright-runner/run-shard.mjs` for each shard. In eager mode this runs inline; in Celery mode the backend enqueues execution to Redis and the worker processes the suite. Both paths persist shard results, execution-event audit records, searchable indexed documents, and evidence JSON files under `backend/data/evidence/`.

## Backend

```powershell
cd backend
python -m pip install -r requirements.txt
uvicorn main:app --reload --port 8091
```

## Frontend

```powershell
cd frontend
npm install
npm run dev
```

## Docker Compose

```powershell
docker compose up --build
```

## Verified

- `cd backend && python -m pytest tests`
- `cd frontend && npm run build`
- `cd playwright-runner && node run-shard.mjs`
- `cd backend && python -c "import celery, redis; print(celery.__version__)"`
- `cd backend && python -c "import elasticsearch; print(elasticsearch.__versionstr__)"`

## Notes

This is now a very strong local platform scaffold aligned to the architecture guide. Enterprise integrations such as live Pinecone, Kafka brokers, Vault, SSO, and real Kubernetes job execution are still represented with local or stubbed modules and manifests rather than fully wired production integrations.
