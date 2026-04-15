-- =============================================================================
-- V2__create_transactions.sql
-- Creates the range-partitioned transactions table.
--
-- Author:  Ravi Kafley
-- Version: 1.0.0
--
-- DESIGN NOTES
-- ─────────────────────────────────────────────────────────────────────────────
-- 1. PARTITION BY RANGE (created_at): Transactions are partitioned monthly.
--    PostgreSQL's partition pruning eliminates non-matching partitions at query
--    planning time, making date-range queries significantly faster.
--
-- 2. FK to accounts(id): Enforces referential integrity at the DB level.
--    An account cannot be deleted while it has associated transactions (audit trail).
--
-- 3. NUMERIC(18,2) for amount: Monetary amounts must never use FLOAT/DOUBLE.
--    Negative values represent credits/refunds.
--
-- 4. fraud_score NUMERIC(3,2): Stores ML-computed probability [0.00–1.00].
--    NULLABLE — populated asynchronously by the fraud-detection-service after insert.
--
-- 5. Partitions created here: 2025-01, 2025-02, 2025-03, 2025-04.
--    IMPORTANT: New monthly partitions must be created in advance (e.g., via a
--    scheduled job or a future Flyway migration). PostgreSQL will raise an error
--    if a row is inserted with a created_at value that falls outside all partitions.
--    Consider pg_partman for automated partition management in production.
--
-- ⚠ RUNTIME RISKS
-- ─────────────────────────────────────────────────────────────────────────────
-- • Missing partition: If a new month's partition is not created before the 1st
--   of that month, all inserts will fail with a "no partition of relation found"
--   error. This is a P0 operational risk — set up automated partition creation.
-- • Indexes on the parent table are inherited by child partitions in PG 11+.
--   However, indexes must be created on the parent; they cannot be added to
--   individual partitions in the same way.
-- • Adding a NOT NULL column to a partitioned table requires altering EACH
--   partition individually — plan schema changes carefully.
-- =============================================================================

CREATE TABLE IF NOT EXISTS transactions (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    account_id   UUID         NOT NULL REFERENCES accounts(id),
    amount       NUMERIC(18,2) NOT NULL,
    merchant     VARCHAR(255),           -- NULL for internal transfers
    category     VARCHAR(50),            -- Populated by ML classification; may be NULL
    fraud_score  NUMERIC(3,2),           -- [0.00-1.00]; NULL = not yet scored
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'CLEARED', 'BLOCKED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
) PARTITION BY RANGE (created_at);

-- ─────────────────────────────────────────────────────────────────────────────
-- Monthly partitions for 2025
-- Each partition covers [start, end) — the upper bound is exclusive.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS transactions_2025_01
    PARTITION OF transactions
    FOR VALUES FROM ('2025-01-01 00:00:00+00') TO ('2025-02-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2025_02
    PARTITION OF transactions
    FOR VALUES FROM ('2025-02-01 00:00:00+00') TO ('2025-03-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2025_03
    PARTITION OF transactions
    FOR VALUES FROM ('2025-03-01 00:00:00+00') TO ('2025-04-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2025_04
    PARTITION OF transactions
    FOR VALUES FROM ('2025-04-01 00:00:00+00') TO ('2025-05-01 00:00:00+00');

-- Monthly partitions for 2026
CREATE TABLE IF NOT EXISTS transactions_2026_01
    PARTITION OF transactions
    FOR VALUES FROM ('2026-01-01 00:00:00+00') TO ('2026-02-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2026_02
    PARTITION OF transactions
    FOR VALUES FROM ('2026-02-01 00:00:00+00') TO ('2026-03-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2026_03
    PARTITION OF transactions
    FOR VALUES FROM ('2026-03-01 00:00:00+00') TO ('2026-04-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2026_04
    PARTITION OF transactions
    FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS transactions_2026_05
    PARTITION OF transactions
    FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00');

-- ─────────────────────────────────────────────────────────────────────────────
-- Indexes — created on the parent table; PostgreSQL propagates to partitions.
-- ─────────────────────────────────────────────────────────────────────────────

-- Primary query predicate: account_id + date range (supports partition pruning)
CREATE INDEX IF NOT EXISTS idx_tx_account_id   ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_tx_created_at   ON transactions(created_at);

-- Composite index for the most common query pattern:
--   SELECT * FROM transactions WHERE account_id = ? AND created_at BETWEEN ? AND ?
CREATE INDEX IF NOT EXISTS idx_tx_account_date ON transactions(account_id, created_at DESC);

-- Fraud score index: used by the compliance/fraud dashboard to find high-risk transactions
-- Partial index (IS NOT NULL) avoids indexing un-scored rows, keeping the index lean.
CREATE INDEX IF NOT EXISTS idx_tx_fraud_score
    ON transactions(fraud_score)
    WHERE fraud_score IS NOT NULL;

-- Status index: used for operational queries (e.g., find all BLOCKED transactions)
CREATE INDEX IF NOT EXISTS idx_tx_status ON transactions(status);
