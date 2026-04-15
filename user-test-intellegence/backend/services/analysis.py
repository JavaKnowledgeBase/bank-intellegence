from __future__ import annotations

from collections import Counter

from agents.test_generator import TestGeneratorAgent
from core.repositories import ExecutionEventRepository, ScoutRepository, SuiteRunRepository, TestCaseRepository
from tools.openapi_tool import OpenAPITool


class AnalysisService:
    def __init__(self) -> None:
        self.test_cases = TestCaseRepository()
        self.runs = SuiteRunRepository()
        self.events = ExecutionEventRepository()
        self.scout = ScoutRepository()
        self.generator = TestGeneratorAgent()
        self.openapi = OpenAPITool()

    def failure_analysis(self) -> dict:
        runs = self.runs.list()
        failures = []
        counter: Counter[str] = Counter()
        for run in runs:
            for shard in run.shards:
                for message in shard.failure_messages:
                    counter[message] += 1
                    failures.append(
                        {
                            "suite_run_id": run.id,
                            "service": run.service,
                            "message": message,
                            "shard_number": shard.shard_number,
                        }
                    )
        return {
            "total_failures": len(failures),
            "patterns": [{"message": msg, "count": count} for msg, count in counter.most_common()],
            "recent_failures": failures[:10],
        }

    def generation_suggestions(self, service: str) -> dict:
        endpoints = self.openapi.coverage_targets(service)
        existing = {case.endpoint for case in self.test_cases.list(service=service)}
        gaps = [endpoint for endpoint in endpoints if endpoint not in existing]
        suggestions = [item.__dict__ for item in self.generator.suggest(service, gaps)]
        return {"service": service, "coverage_gaps": gaps, "suggestions": suggestions}

    def scout_stats(self) -> dict:
        items = self.scout.list()
        by_status = Counter(item.safety_status for item in items)
        by_service = Counter(item.proposed_service for item in items)
        return {
            "total": len(items),
            "by_status": dict(by_status),
            "by_service": dict(by_service),
        }

    def execution_events(self, suite_run_id: str | None = None) -> dict:
        return {"items": self.events.list_recent(suite_run_id=suite_run_id)}
