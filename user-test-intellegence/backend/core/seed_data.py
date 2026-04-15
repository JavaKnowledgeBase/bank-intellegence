from __future__ import annotations

from datetime import timedelta

from core.database import db, utc_now


def seed_database() -> None:
    now = utc_now()
    if db.count("test_cases") == 0:
        samples = [
            (
                "tc-customer-create-001",
                "Create customer profile with synthetic identity",
                "Validate customer-agent can create a profile using TEST_ data and persist the record.",
                "customer-agent-service",
                "/customers",
                "critical",
                "validated",
                "manual",
                '["onboarding","pii-governance"]',
                '[{"order":1,"action":"POST /customers with TEST_ profile","expected_response":"201 Created and customer id returned"},{"order":2,"action":"GET /customers/{id}","expected_response":"Profile is retrievable and masked fields stay synthetic"}]',
                "passed",
                now.isoformat(),
                now.isoformat(),
            ),
            (
                "tc-fraud-score-002",
                "Assess fraudulent transaction risk",
                "Ensure fraud scoring handles a high-risk synthetic payment without timing out.",
                "fraud-detection-service",
                "/fraud/check",
                "high",
                "active",
                "generated",
                '["risk","regression"]',
                '[{"order":1,"action":"POST /fraud/check with TEST_ transaction payload","expected_response":"200 OK with score >= configured risk threshold"}]',
                "not_run",
                now.isoformat(),
                now.isoformat(),
            ),
            (
                "tc-loan-prescreen-003",
                "Prescreen loan application eligibility",
                "Cover prescreen rules for a synthetic low-debt applicant with stable employment.",
                "loan-prescreen-service",
                "/prescreen",
                "high",
                "active",
                "scout",
                '["eligibility","coverage-gap"]',
                '[{"order":1,"action":"POST /prescreen with TEST_ applicant","expected_response":"200 OK with eligible decision and reason codes"}]',
                "failed",
                now.isoformat(),
                now.isoformat(),
            ),
        ]
        for row in samples:
            db.execute(
                """
                INSERT INTO test_cases (
                    id, title, scenario_description, target_service, endpoint,
                    priority, status, source, tags, steps, last_run_status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row,
            )

    if db.count("suite_runs") == 0:
        started = now - timedelta(minutes=18)
        completed = started + timedelta(minutes=2)
        db.execute(
            """
            INSERT INTO suite_runs (
                id, service, status, trigger_type, trigger_actor, commit_sha, branch,
                total_tests, total_shards, completed_shards, passed, failed, errored,
                duration_ms, started_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "suite-seeded-001",
                "customer-agent-service",
                "passed",
                "deploy",
                "github-actions",
                "abc123def456",
                "main",
                2,
                1,
                1,
                2,
                0,
                0,
                119000,
                started.isoformat(),
                completed.isoformat(),
            ),
        )
        db.execute(
            """
            INSERT INTO shard_runs (
                suite_run_id, shard_number, status, passed, failed, errored, duration_ms,
                test_case_ids, failure_messages
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                "suite-seeded-001",
                0,
                "passed",
                2,
                0,
                0,
                119000,
                '["tc-customer-create-001","tc-fraud-score-002"]',
                '[]',
            ),
        )

    if db.count("scout_observations") == 0:
        rows = [
            (
                "scout-001",
                "Contract testing pattern for fraud scoring drift",
                "https://martinfowler.com/articles/practical-test-pyramid.html",
                "martinfowler.com",
                "Highlights lean contract coverage that can reduce slow end-to-end suites for risk services.",
                "accepted",
                "fraud-detection-service",
                '["contracts","scout"]',
                now.isoformat(),
            ),
            (
                "scout-002",
                "Synthetic data validation checklist",
                "https://owasp.org/www-project-web-security-testing-guide/",
                "owasp.org",
                "Useful checklist for handling synthetic identities and preventing accidental live PII in test fixtures.",
                "needs_review",
                "customer-agent-service",
                '["pii","governance"]',
                (now - timedelta(hours=5)).isoformat(),
            ),
        ]
        for row in rows:
            db.execute(
                """
                INSERT INTO scout_observations (
                    id, title, source_url, domain, summary, safety_status,
                    proposed_service, tags, discovered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row,
            )

    if db.count("improvement_proposals") == 0:
        rows = [
            (
                "imp-001",
                "Reduce Playwright flakiness with explicit waits",
                "test_generator",
                "Add wait strategy guidance to generated scripts when asynchronous API responses drive follow-up assertions.",
                "Estimated flaky run reduction from 18% to 6% in shadow analysis.",
                "shadow_mode",
                now.isoformat(),
            ),
            (
                "imp-002",
                "Tune duplicate threshold for coverage gap additions",
                "vector_store",
                "Lower false duplicate rejection rate for near-match prescreen tests by refining semantic thresholding.",
                "Expected +12% acceptance of truly novel candidate tests.",
                "proposed",
                (now - timedelta(days=1)).isoformat(),
            ),
        ]
        for row in rows:
            db.execute(
                """
                INSERT INTO improvement_proposals (
                    id, title, area, summary, expected_impact, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                row,
            )
