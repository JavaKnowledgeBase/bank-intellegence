CREATE TABLE IF NOT EXISTS test_cases (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    scenario_description TEXT NOT NULL,
    target_service TEXT NOT NULL,
    endpoint TEXT NOT NULL,
    priority TEXT NOT NULL,
    status TEXT NOT NULL,
    source TEXT NOT NULL,
    tags TEXT NOT NULL,
    steps TEXT NOT NULL,
    last_run_status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS suite_runs (
    id TEXT PRIMARY KEY,
    service TEXT NOT NULL,
    status TEXT NOT NULL,
    trigger_type TEXT NOT NULL,
    trigger_actor TEXT NOT NULL,
    commit_sha TEXT,
    branch TEXT,
    total_tests INTEGER NOT NULL,
    total_shards INTEGER NOT NULL,
    completed_shards INTEGER NOT NULL,
    passed INTEGER NOT NULL,
    failed INTEGER NOT NULL,
    errored INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE IF NOT EXISTS shard_runs (
    suite_run_id TEXT NOT NULL,
    shard_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    passed INTEGER NOT NULL,
    failed INTEGER NOT NULL,
    errored INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    test_case_ids TEXT NOT NULL,
    failure_messages TEXT NOT NULL,
    PRIMARY KEY (suite_run_id, shard_number)
);

CREATE TABLE IF NOT EXISTS scout_observations (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    source_url TEXT NOT NULL,
    domain TEXT NOT NULL,
    summary TEXT NOT NULL,
    safety_status TEXT NOT NULL,
    proposed_service TEXT NOT NULL,
    tags TEXT NOT NULL,
    discovered_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS improvement_proposals (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    area TEXT NOT NULL,
    summary TEXT NOT NULL,
    expected_impact TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS execution_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    suite_run_id TEXT NOT NULL,
    service TEXT NOT NULL,
    event_type TEXT NOT NULL,
    level TEXT NOT NULL,
    message TEXT NOT NULL,
    event_payload TEXT NOT NULL,
    created_at TEXT NOT NULL
);
