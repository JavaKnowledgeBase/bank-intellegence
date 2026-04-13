-- CSIP Database Schema — PostgreSQL 16 + TimescaleDB
-- Tables are created by SQLAlchemy on startup (see core/database.py)
-- This file serves as reference and for manual inspection/migration.

-- Enable TimescaleDB for time-series metrics (optional)
-- CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;

-- ── App Configurations ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_configs (
    id                    VARCHAR(36) PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    team_id               VARCHAR(100) NOT NULL,
    namespace             VARCHAR(100) NOT NULL,
    tier                  VARCHAR(10) NOT NULL DEFAULT 'p2',
    base_url              VARCHAR(512) NOT NULL,
    health_path           VARCHAR(200) DEFAULT '/actuator/health',
    metrics_path          VARCHAR(200) DEFAULT '/actuator/prometheus',
    kafka_topics_produced JSONB DEFAULT '[]',
    k8s_deployment_name   VARCHAR(200),
    k8s_namespace         VARCHAR(100),
    repo_url              VARCHAR(512),
    repo_branch           VARCHAR(100) DEFAULT 'main',
    codeowners            JSONB DEFAULT '[]',
    tech_stack            VARCHAR(100) DEFAULT 'java-spring-boot',
    description           TEXT,
    polling_interval_secs INTEGER DEFAULT 60,
    smoke_test_endpoint   VARCHAR(512),
    fix_auto_pr           BOOLEAN DEFAULT TRUE,
    fix_require_human     BOOLEAN DEFAULT TRUE,
    monitoring_paused_until VARCHAR(50),
    status                VARCHAR(20) DEFAULT 'unknown',
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ DEFAULT NOW(),
    updated_at            TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_app_configs_team_id ON app_configs(team_id);
CREATE INDEX IF NOT EXISTS idx_app_configs_status ON app_configs(status);

-- ── Issues ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS issues (
    id                    VARCHAR(36) PRIMARY KEY,
    app_id                VARCHAR(36) NOT NULL,
    team_id               VARCHAR(100) NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    category              VARCHAR(30) NOT NULL,
    severity              VARCHAR(5) NOT NULL,
    confidence            FLOAT DEFAULT 0.0,
    root_cause_summary    TEXT,
    technical_detail      TEXT,
    classification_method VARCHAR(20) DEFAULT 'unknown',
    error_cluster_id      VARCHAR(64),
    error_count           INTEGER DEFAULT 1,
    first_seen_at         TIMESTAMPTZ DEFAULT NOW(),
    last_seen_at          TIMESTAMPTZ DEFAULT NOW(),
    affected_file         VARCHAR(500),
    affected_class        VARCHAR(200),
    affected_method       VARCHAR(200),
    fix_branch            VARCHAR(200),
    fix_pr_url            VARCHAR(512),
    fix_pr_number         INTEGER,
    fix_attempts          INTEGER DEFAULT 0,
    fix_attempt_history   JSONB DEFAULT '[]',
    pagerduty_incident_id VARCHAR(100),
    status                VARCHAR(30) NOT NULL DEFAULT 'open',
    final_outcome         VARCHAR(30),
    llm_tokens_used       INTEGER DEFAULT 0,
    llm_cost_usd          FLOAT DEFAULT 0.0,
    stack_trace           TEXT,
    raw_error             TEXT,
    created_at            TIMESTAMPTZ DEFAULT NOW(),
    updated_at            TIMESTAMPTZ DEFAULT NOW(),
    resolved_at           TIMESTAMPTZ,
    resolution_notes      TEXT
);
CREATE INDEX IF NOT EXISTS idx_issues_app_id ON issues(app_id);
CREATE INDEX IF NOT EXISTS idx_issues_team_id ON issues(team_id);
CREATE INDEX IF NOT EXISTS idx_issues_status ON issues(status);
CREATE INDEX IF NOT EXISTS idx_issues_category ON issues(category);
CREATE INDEX IF NOT EXISTS idx_issues_severity ON issues(severity);
CREATE INDEX IF NOT EXISTS idx_issues_created_at ON issues(created_at DESC);

-- ── Audit Records (append-only) ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS audit_records (
    id              VARCHAR(36) PRIMARY KEY,
    sequence_number INTEGER NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    app_id          VARCHAR(36),
    issue_id        VARCHAR(36),
    team_id         VARCHAR(100),
    actor           VARCHAR(200) NOT NULL,
    actor_ip        VARCHAR(50),
    summary         VARCHAR(1000) NOT NULL,
    details         JSONB NOT NULL DEFAULT '{}',
    diff            TEXT,
    diff_hash       VARCHAR(64),
    previous_hash   VARCHAR(64) DEFAULT 'GENESIS',
    record_hash     VARCHAR(64) NOT NULL,
    timestamp       TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_event_type ON audit_records(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_app_id ON audit_records(app_id);
CREATE INDEX IF NOT EXISTS idx_audit_issue_id ON audit_records(issue_id);
CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_records(timestamp DESC);

-- ── Prompt Registry ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS prompt_registry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_type      VARCHAR(50) NOT NULL,
    version         INTEGER NOT NULL,
    is_active       BOOLEAN DEFAULT FALSE,
    system_prompt   TEXT NOT NULL,
    user_template   TEXT NOT NULL,
    performance_metrics JSONB DEFAULT '{}',
    created_by      VARCHAR(100),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    activated_at    TIMESTAMPTZ,
    UNIQUE(agent_type, version)
);

-- ── Health History (TimescaleDB hypertable if extension present) ──────────────
CREATE TABLE IF NOT EXISTS health_history (
    time       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    app_id     VARCHAR(36) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    response_ms INTEGER,
    detail     JSONB DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_health_history_app ON health_history(app_id, time DESC);
-- SELECT create_hypertable('health_history', 'time', if_not_exists => TRUE);

-- ── Trigger: update updated_at ────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_app_configs_updated_at
    BEFORE UPDATE ON app_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE OR REPLACE TRIGGER trg_issues_updated_at
    BEFORE UPDATE ON issues
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
