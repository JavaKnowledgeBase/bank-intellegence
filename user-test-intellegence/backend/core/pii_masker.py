from __future__ import annotations

import re


class PIIMasker:
    email_pattern = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
    phone_pattern = re.compile(r"\b\d{3}[-.]?\d{3}[-.]?\d{4}\b")
    ssn_pattern = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")

    def find_pii(self, text: str) -> list[str]:
        findings: list[str] = []
        if self.email_pattern.search(text):
            findings.append("email")
        if self.phone_pattern.search(text):
            findings.append("phone")
        if self.ssn_pattern.search(text):
            findings.append("ssn")
        return findings

    def mask(self, text: str) -> str:
        masked = self.email_pattern.sub("[MASKED_EMAIL]", text)
        masked = self.phone_pattern.sub("[MASKED_PHONE]", masked)
        masked = self.ssn_pattern.sub("[MASKED_SSN]", masked)
        return masked
