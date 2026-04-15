from __future__ import annotations

import json
from datetime import datetime
from typing import Any

from models.scout import ImprovementProposal, ScoutObservation
from models.test_case import TestCase, TestCaseCreate
from models.test_run import ShardResult, SuiteRun

from core.database import db


def _parse_dt(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None


class TestCaseRepository:
    def list(self, service: str | None = None, status: str | None = None) -> list[TestCase]:
        clauses = []
        params: list[str] = []
        if service:
            clauses.append("target_service = ?")
            params.append(service)
        if status:
            clauses.append("status = ?")
            params.append(status)
        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        rows = db.fetchall(
            f"SELECT * FROM test_cases {where_sql} ORDER BY updated_at DESC",
            tuple(params),
        )
        return [self._from_row(row) for row in rows]

    def get(self, test_case_id: str) -> TestCase | None:
        row = db.fetchone("SELECT * FROM test_cases WHERE id = ?", (test_case_id,))
        return self._from_row(row) if row else None

    def create(self, payload: TestCaseCreate, test_case_id: str, created_at: datetime) -> TestCase:
        db.execute(
            """
            INSERT INTO test_cases (
                id, title, scenario_description, target_service, endpoint, priority,
                status, source, tags, steps, last_run_status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                test_case_id,
                payload.title,
                payload.scenario_description,
                payload.target_service,
                payload.endpoint,
                payload.priority,
                "active",
                payload.source,
                json.dumps(payload.tags),
                json.dumps([step.model_dump() for step in payload.steps]),
                "not_run",
                created_at.isoformat(),
                created_at.isoformat(),
            ),
        )
        return self.get(test_case_id)

    def update_last_run_status(self, test_case_id: str, status: str) -> None:
        existing = self.get(test_case_id)
        if existing is None:
            return
        db.execute(
            "UPDATE test_cases SET last_run_status = ?, updated_at = ? WHERE id = ?",
            (status, datetime.now().isoformat(), test_case_id),
        )

    def _from_row(self, row: dict) -> TestCase:
        return TestCase(
            id=row["id"],
            title=row["title"],
            scenario_description=row["scenario_description"],
            target_service=row["target_service"],
            endpoint=row["endpoint"],
            priority=row["priority"],
            status=row["status"],
            source=row["source"],
            tags=json.loads(row["tags"]),
            steps=json.loads(row["steps"]),
            last_run_status=row["last_run_status"],
            created_at=datetime.fromisoformat(row["created_at"]),
            updated_at=datetime.fromisoformat(row["updated_at"]),
        )


class SuiteRunRepository:
    def list(self) -> list[SuiteRun]:
        rows = db.fetchall("SELECT * FROM suite_runs ORDER BY started_at DESC")
        return [self.get(row["id"]) for row in rows]

    def get(self, suite_run_id: str) -> SuiteRun | None:
        row = db.fetchone("SELECT * FROM suite_runs WHERE id = ?", (suite_run_id,))
        if not row:
            return None
        shard_rows = db.fetchall(
            "SELECT * FROM shard_runs WHERE suite_run_id = ? ORDER BY shard_number",
            (suite_run_id,),
        )
        shards = [
            ShardResult(
                suite_run_id=shard["suite_run_id"],
                shard_number=shard["shard_number"],
                status=shard["status"],
                passed=shard["passed"],
                failed=shard["failed"],
                errored=shard["errored"],
                duration_ms=shard["duration_ms"],
                test_case_ids=json.loads(shard["test_case_ids"]),
                failure_messages=json.loads(shard["failure_messages"]),
            )
            for shard in shard_rows
        ]
        return SuiteRun(
            id=row["id"],
            service=row["service"],
            status=row["status"],
            trigger=row["trigger_type"],
            trigger_actor=row["trigger_actor"],
            commit_sha=row["commit_sha"],
            branch=row["branch"],
            total_tests=row["total_tests"],
            total_shards=row["total_shards"],
            completed_shards=row["completed_shards"],
            passed=row["passed"],
            failed=row["failed"],
            errored=row["errored"],
            duration_ms=row["duration_ms"],
            started_at=_parse_dt(row["started_at"]),
            completed_at=_parse_dt(row["completed_at"]),
            shards=shards,
        )

    def create(
        self,
        suite_run_id: str,
        service: str,
        trigger: str,
        trigger_actor: str,
        total_tests: int,
        total_shards: int,
        started_at: datetime,
        commit_sha: str | None = None,
        branch: str | None = None,
    ) -> SuiteRun:
        db.execute(
            """
            INSERT INTO suite_runs (
                id, service, status, trigger_type, trigger_actor, commit_sha, branch,
                total_tests, total_shards, completed_shards, passed, failed, errored,
                duration_ms, started_at, completed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                suite_run_id,
                service,
                "queued",
                trigger,
                trigger_actor,
                commit_sha,
                branch,
                total_tests,
                total_shards,
                0,
                0,
                0,
                0,
                0,
                started_at.isoformat(),
                None,
            ),
        )
        return self.get(suite_run_id)

    def upsert_shard(self, shard: ShardResult) -> None:
        db.execute(
            """
            INSERT INTO shard_runs (
                suite_run_id, shard_number, status, passed, failed, errored,
                duration_ms, test_case_ids, failure_messages
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(suite_run_id, shard_number) DO UPDATE SET
                status = excluded.status,
                passed = excluded.passed,
                failed = excluded.failed,
                errored = excluded.errored,
                duration_ms = excluded.duration_ms,
                test_case_ids = excluded.test_case_ids,
                failure_messages = excluded.failure_messages
            """,
            (
                shard.suite_run_id,
                shard.shard_number,
                shard.status,
                shard.passed,
                shard.failed,
                shard.errored,
                shard.duration_ms,
                json.dumps(shard.test_case_ids),
                json.dumps(shard.failure_messages),
            ),
        )

    def update_status(
        self,
        suite_run_id: str,
        *,
        status: str,
        completed_shards: int,
        passed: int,
        failed: int,
        errored: int,
        duration_ms: int,
        completed_at: datetime | None = None,
    ) -> None:
        db.execute(
            """
            UPDATE suite_runs
            SET status = ?, completed_shards = ?, passed = ?, failed = ?, errored = ?,
                duration_ms = ?, completed_at = ?
            WHERE id = ?
            """,
            (
                status,
                completed_shards,
                passed,
                failed,
                errored,
                duration_ms,
                completed_at.isoformat() if completed_at else None,
                suite_run_id,
            ),
        )


class ExecutionEventRepository:
    def create(self, suite_run_id: str, service: str, event_type: str, level: str, message: str, payload: dict[str, Any] | None = None) -> None:
        db.execute(
            """
            INSERT INTO execution_events (
                suite_run_id, service, event_type, level, message, event_payload, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                suite_run_id,
                service,
                event_type,
                level,
                message,
                json.dumps(payload or {}),
                datetime.now().isoformat(),
            ),
        )

    def list_recent(self, suite_run_id: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        if suite_run_id:
            rows = db.fetchall(
                "SELECT * FROM execution_events WHERE suite_run_id = ? ORDER BY id DESC LIMIT ?",
                (suite_run_id, limit),
            )
        else:
            rows = db.fetchall(
                "SELECT * FROM execution_events ORDER BY id DESC LIMIT ?",
                (limit,),
            )
        return [
            {
                **row,
                "event_payload": json.loads(row["event_payload"]),
            }
            for row in rows
        ]


class ScoutRepository:
    def list(self) -> list[ScoutObservation]:
        rows = db.fetchall("SELECT * FROM scout_observations ORDER BY discovered_at DESC")
        return [
            ScoutObservation(
                id=row["id"],
                title=row["title"],
                source_url=row["source_url"],
                domain=row["domain"],
                summary=row["summary"],
                safety_status=row["safety_status"],
                proposed_service=row["proposed_service"],
                tags=json.loads(row["tags"]),
                discovered_at=datetime.fromisoformat(row["discovered_at"]),
            )
            for row in rows
        ]


class ImprovementRepository:
    def list(self) -> list[ImprovementProposal]:
        rows = db.fetchall(
            "SELECT * FROM improvement_proposals ORDER BY created_at DESC"
        )
        return [
            ImprovementProposal(
                id=row["id"],
                title=row["title"],
                area=row["area"],
                summary=row["summary"],
                expected_impact=row["expected_impact"],
                status=row["status"],
                created_at=datetime.fromisoformat(row["created_at"]),
            )
            for row in rows
        ]
