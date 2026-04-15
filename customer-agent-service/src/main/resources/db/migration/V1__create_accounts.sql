-- =============================================================================
-- V1__create_accounts.sql
-- Creates the core accounts table for the Customer Agent Service.
--
-- Author:  Ravi Kafley
-- Version: 1.0.0
--
-- DESIGN NOTES
-- ─────────────────────────────────────────────────────────────────────────────
-- 1. UUID primary key: gen_random_uuid() is native to PostgreSQL 13+.
--    Avoids sequential ID guessing attacks on the API layer.
--
-- 2. NUMERIC(18,2): All monetary values use NUMERIC to avoid floating-point
--    rounding errors (FLOAT/DOUBLE are unsuitable for financial data).
--
-- 3. TIMESTAMPTZ: All timestamps include timezone (UTC). Using plain TIMESTAMP
--    is a common source of DST-related bugs in financial ledger applications.
--
-- 4. CHECK constraints: Enforced at the DB level as a last line of defence
--    even though application-level validation exists. This prevents data
--    corruption from direct DB writes (migrations, admin queries, other services).
--
-- 5. Indexes: Two partial-friendly indexes are created:
--    - idx_accounts_customer_id: High-selectivity; used by every customer query.
--    - idx_accounts_status:      Low-selectivity but speeds up operational queries
--                                filtering by ACTIVE/FROZEN/CLOSED.
--
-- ⚠ RUNTIME RISKS
-- ─────────────────────────────────────────────────────────────────────────────
-- • Adding a NOT NULL column without a DEFAULT to an existing large table will
--   lock the table for the duration of the backfill. Always provide a DEFAULT
--   or use a two-step migration (add nullable → backfill → add NOT NULL constraint).
-- • Dropping a CHECK constraint requires acquiring an ACCESS EXCLUSIVE lock.
--   Never run DDL on this table during peak trading hours.
-- =============================================================================

CREATE TABLE IF NOT EXISTS accounts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   UUID         NOT NULL,
    account_type  VARCHAR(20)  NOT NULL
                      CHECK (account_type IN ('CHECKING', 'SAVINGS', 'CREDIT')),
    balance       NUMERIC(18,2) NOT NULL DEFAULT 0.00,
    status        VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0  -- Optimistic locking counter for R2DBC
);

-- Index: customer_id is the primary query predicate for all account lookups.
-- Expected selectivity: ~1:many (one customer, multiple accounts).
CREATE INDEX IF NOT EXISTS idx_accounts_customer_id ON accounts(customer_id);

-- Index: status is used for filtering ACTIVE accounts in the summary query.
-- Low cardinality (3 values) — useful as a secondary predicate with customer_id.
CREATE INDEX IF NOT EXISTS idx_accounts_status ON accounts(status);

-- Composite index for the most common query pattern:
--   SELECT * FROM accounts WHERE customer_id = ? AND status = 'ACTIVE'
-- Covers both predicates in a single index scan.
CREATE INDEX IF NOT EXISTS idx_accounts_customer_status ON accounts(customer_id, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Trigger: auto-update updated_at on every row modification.
-- Without this, the application layer must explicitly set updated_at, which
-- is error-prone and can be bypassed by direct SQL writes.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
