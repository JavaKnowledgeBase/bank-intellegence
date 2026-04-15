from __future__ import annotations

from dataclasses import dataclass


@dataclass
class GenerationSuggestion:
    title: str
    scenario_description: str
    endpoint: str
    tags: list[str]


class TestGeneratorAgent:
    def suggest(self, service: str, coverage_gaps: list[str]) -> list[GenerationSuggestion]:
        return [
            GenerationSuggestion(
                title=f"Synthetic regression for {service} {endpoint}",
                scenario_description=f"Exercise {endpoint} with TEST_ fixtures and assert stable contract behavior.",
                endpoint=endpoint,
                tags=["generated", "coverage-gap", service],
            )
            for endpoint in coverage_gaps
        ]
