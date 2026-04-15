from __future__ import annotations

from collections import Counter

from core.repositories import SuiteRunRepository, TestCaseRepository


class DashboardService:
    def __init__(self) -> None:
        self.test_cases = TestCaseRepository()
        self.runs = SuiteRunRepository()

    def summary(self) -> dict:
        cases = self.test_cases.list()
        runs = self.runs.list()
        service_counts = Counter(case.target_service for case in cases)
        failing_runs = [run for run in runs if run.status == "failed"]
        passed_runs = [run for run in runs if run.status == "passed"]
        return {
            "totals": {
                "test_cases": len(cases),
                "suite_runs": len(runs),
                "passing_suites": len(passed_runs),
                "failing_suites": len(failing_runs),
            },
            "services": [
                {"service": service, "test_case_count": count}
                for service, count in sorted(service_counts.items())
            ],
            "latest_failures": [
                {
                    "suite_run_id": run.id,
                    "service": run.service,
                    "failed": run.failed,
                    "commit_sha": run.commit_sha,
                }
                for run in failing_runs[:5]
            ],
        }
