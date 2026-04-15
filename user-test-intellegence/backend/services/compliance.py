from __future__ import annotations

from core.repositories import ImprovementRepository, ScoutRepository, SuiteRunRepository, TestCaseRepository


class ComplianceService:
    def __init__(self) -> None:
        self.test_cases = TestCaseRepository()
        self.runs = SuiteRunRepository()
        self.scout = ScoutRepository()
        self.improvements = ImprovementRepository()

    def report(self, month: str, service: str = "all") -> dict:
        runs = self.runs.list()
        cases = self.test_cases.list(service=None if service == "all" else service)
        scoped_runs = [run for run in runs if run.started_at.strftime("%Y-%m") == month and (service == "all" or run.service == service)]
        blocked = len([run for run in scoped_runs if run.status == "failed"])
        allowed = len([run for run in scoped_runs if run.status == "passed"])
        return {
            "month": month,
            "service": service,
            "total_tests": len(cases),
            "suite_runs": len(scoped_runs),
            "deployments_blocked": blocked,
            "deployments_allowed": allowed,
            "scout_activity": len(self.scout.list()),
            "self_improvement_proposals": len(self.improvements.list()),
            "evidence_index": [
                {
                    "suite_run_id": run.id,
                    "service": run.service,
                    "evidence_path": f"s3://ctip-local-evidence/{run.started_at.strftime('%Y-%m-%d')}/{run.id}.json",
                }
                for run in scoped_runs
            ],
        }
