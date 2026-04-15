from __future__ import annotations

import asyncio
import json
import math
import os
import uuid
from pathlib import Path

from api.websocket import manager
from core.config import settings
from core.database import utc_now
from core.evidence_generator import TestEvidenceGenerator
from core.repositories import ExecutionEventRepository, SuiteRunRepository, TestCaseRepository
from models.test_run import ShardResult, ShardResultIn, SuiteRunCreate


class ExecutionService:
    def __init__(self) -> None:
        self.test_cases = TestCaseRepository()
        self.suites = SuiteRunRepository()
        self.events = ExecutionEventRepository()
        self.evidence = TestEvidenceGenerator()
        self.runner_path = Path(__file__).resolve().parents[2] / "playwright-runner" / "run-shard.mjs"

    async def start_suite(self, service: str, payload: SuiteRunCreate) -> str:
        tests = [tc for tc in self.test_cases.list(service=service) if tc.status != "deprecated"]
        suite_id = str(uuid.uuid4())
        total_tests = len(tests)
        total_shards = max(1, math.ceil(max(1, total_tests) / settings.shard_size))
        self.suites.create(
            suite_run_id=suite_id,
            service=service,
            trigger=payload.trigger,
            trigger_actor=payload.trigger_actor,
            total_tests=total_tests,
            total_shards=total_shards,
            started_at=utc_now(),
            commit_sha=payload.commit_sha,
            branch=payload.branch,
        )
        self.events.create(suite_id, service, "suite_queued", "info", "Suite queued for execution", {"total_tests": total_tests, "total_shards": total_shards, "queue_mode": settings.task_queue_mode})
        await manager.broadcast(
            {
                "type": "suite_started",
                "suite_run_id": suite_id,
                "service": service,
                "total_tests": total_tests,
                "total_shards": total_shards,
            }
        )
        if settings.task_queue_mode == "celery":
            from workers.execution_tasks import run_suite_execution_task

            run_suite_execution_task.delay(suite_id, service)
        elif settings.execution_mode == "local":
            if os.environ.get("PYTEST_CURRENT_TEST"):
                await self.run_queued_suite(suite_id, service)
            else:
                asyncio.create_task(self.run_queued_suite(suite_id, service))
        else:
            asyncio.create_task(self._simulate_execution(suite_id, service, tests))
        return suite_id

    async def run_queued_suite(self, suite_run_id: str, service: str) -> None:
        tests = [tc for tc in self.test_cases.list(service=service) if tc.status != "deprecated"]
        await self._execute_local_suite(suite_run_id, service, tests)

    async def record_shard_result(self, payload: ShardResultIn) -> None:
        suite = self.suites.get(payload.suite_run_id)
        if suite is None:
            return
        shard = ShardResult(
            suite_run_id=payload.suite_run_id,
            shard_number=payload.shard_number,
            status="failed" if payload.failed or payload.errored else "passed",
            passed=payload.passed,
            failed=payload.failed,
            errored=payload.errored,
            duration_ms=payload.duration_ms,
            test_case_ids=payload.test_case_ids,
            failure_messages=payload.failure_messages,
        )
        self.suites.upsert_shard(shard)
        self.events.create(payload.suite_run_id, suite.service, "shard_reported", "info", f"Shard {payload.shard_number} reported", payload.model_dump())
        await self._recalculate_suite(payload.suite_run_id)

    async def _execute_local_suite(self, suite_run_id: str, service: str, tests: list) -> None:
        self.suites.update_status(
            suite_run_id,
            status="running",
            completed_shards=0,
            passed=0,
            failed=0,
            errored=0,
            duration_ms=0,
        )
        self.events.create(suite_run_id, service, "suite_started", "info", "Local runner execution started")
        shard_size = settings.shard_size
        tasks = []
        for shard_number, start in enumerate(range(0, max(1, len(tests)), shard_size)):
            shard_tests = tests[start : start + shard_size] or tests
            tasks.append(asyncio.create_task(self._run_shard_process(suite_run_id, service, shard_number, shard_tests)))
        if tasks:
            await asyncio.gather(*tasks)

    async def _run_shard_process(self, suite_run_id: str, service: str, shard_number: int, shard_tests: list) -> None:
        test_ids = [tc.id for tc in shard_tests]
        self.events.create(suite_run_id, service, "shard_started", "info", f"Shard {shard_number} launched", {"test_case_ids": test_ids})
        try:
            process = await asyncio.create_subprocess_exec(
                "node",
                str(self.runner_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env={
                    **os.environ,
                    "SUITE_RUN_ID": suite_run_id,
                    "SHARD_NUMBER": str(shard_number),
                    "TEST_CASE_IDS": ",".join(test_ids),
                    "TARGET_SERVICE": service,
                },
            )
            stdout, stderr = await process.communicate()
            if process.returncode != 0:
                message = stderr.decode().strip() or stdout.decode().strip() or "Runner failed"
                self.events.create(suite_run_id, service, "shard_failed", "error", message, {"shard_number": shard_number})
                await self.record_shard_result(
                    ShardResultIn(
                        suite_run_id=suite_run_id,
                        shard_number=shard_number,
                        passed=0,
                        failed=len(test_ids),
                        errored=1,
                        duration_ms=0,
                        test_case_ids=test_ids,
                        failure_messages=[message],
                    )
                )
                return
            payload = json.loads(stdout.decode())
            await self.record_shard_result(
                ShardResultIn(
                    suite_run_id=suite_run_id,
                    shard_number=shard_number,
                    passed=payload["passed"],
                    failed=payload["failed"],
                    errored=payload.get("errored", 0),
                    duration_ms=payload["duration_ms"],
                    test_case_ids=payload["test_case_ids"],
                    failure_messages=payload.get("failure_messages", []),
                )
            )
        except Exception as exc:
            self.events.create(suite_run_id, service, "shard_failed", "error", str(exc), {"shard_number": shard_number})
            await self.record_shard_result(
                ShardResultIn(
                    suite_run_id=suite_run_id,
                    shard_number=shard_number,
                    passed=0,
                    failed=len(test_ids),
                    errored=1,
                    duration_ms=0,
                    test_case_ids=test_ids,
                    failure_messages=[str(exc)],
                )
            )

    async def _simulate_execution(self, suite_run_id: str, service: str, tests: list) -> None:
        self.suites.update_status(
            suite_run_id,
            status="running",
            completed_shards=0,
            passed=0,
            failed=0,
            errored=0,
            duration_ms=0,
        )
        shard_size = settings.shard_size
        for shard_number, start in enumerate(range(0, max(1, len(tests)), shard_size)):
            await asyncio.sleep(0.15)
            shard_tests = tests[start : start + shard_size] or tests
            failed = 1 if shard_number == 0 and service == "loan-prescreen-service" else 0
            passed = max(0, len(shard_tests) - failed)
            shard = ShardResult(
                suite_run_id=suite_run_id,
                shard_number=shard_number,
                status="failed" if failed else "passed",
                passed=passed,
                failed=failed,
                errored=0,
                duration_ms=45000 + shard_number * 5000,
                test_case_ids=[tc.id for tc in shard_tests],
                failure_messages=(["Eligibility rule mismatch on synthetic income band"] if failed else []),
            )
            self.suites.upsert_shard(shard)
            for test_case in shard_tests:
                self.test_cases.update_last_run_status(test_case.id, "failed" if failed else "passed")
            await self._recalculate_suite(suite_run_id)

    async def _recalculate_suite(self, suite_run_id: str) -> None:
        suite = self.suites.get(suite_run_id)
        if suite is None:
            return
        completed_shards = len(suite.shards)
        passed = sum(shard.passed for shard in suite.shards)
        failed = sum(shard.failed for shard in suite.shards)
        errored = sum(shard.errored for shard in suite.shards)
        duration_ms = sum(shard.duration_ms for shard in suite.shards)
        completed_at = None
        status = "running"
        if completed_shards >= suite.total_shards:
            completed_at = utc_now()
            status = "failed" if failed or errored else "passed"
        self.suites.update_status(
            suite_run_id,
            status=status,
            completed_shards=completed_shards,
            passed=passed,
            failed=failed,
            errored=errored,
            duration_ms=duration_ms,
            completed_at=completed_at,
        )
        refreshed = self.suites.get(suite_run_id)
        if refreshed and refreshed.completed_at:
            evidence_path = self.evidence.generate(refreshed.model_dump(mode="json"))
            self.events.create(suite_run_id, refreshed.service, "suite_completed", "info", f"Suite completed with status {refreshed.status}", {"evidence_path": evidence_path})
        await manager.broadcast(
            {
                "type": "suite_updated",
                "suite_run": refreshed.model_dump(mode="json"),
            }
        )
