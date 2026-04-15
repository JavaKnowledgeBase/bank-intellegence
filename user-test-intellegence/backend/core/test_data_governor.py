from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass
class GovernanceResult:
    accepted: bool
    violations: list[str]


class TestDataGovernor:
    required_prefix = "TEST_"

    def validate_strings(self, values: list[str]) -> GovernanceResult:
        violations: list[str] = []
        for value in values:
            if not value:
                continue
            for token in re.findall(r"[A-Za-z0-9_@.\-/]+", value):
                if any(char.isdigit() for char in token) or "@" in token or token.isupper():
                    if token.startswith(("http", "/api", "/")):
                        continue
                    if token.startswith(self.required_prefix):
                        continue
                    violations.append(f"Synthetic test data token must use {self.required_prefix}: {token}")
        return GovernanceResult(accepted=not violations, violations=violations)
