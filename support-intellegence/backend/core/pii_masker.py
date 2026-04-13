"""
PII Masking pipeline using Microsoft Presidio + financial-services regex patterns.
All log content passes through here before reaching Claude or Pinecone.
"""
from __future__ import annotations

import re
import logging
from typing import Optional

logger = logging.getLogger(__name__)

# Financial-services–specific patterns applied BEFORE Presidio
_FINANCIAL_PATTERNS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"\b\d{9,17}\b"), "<ACCOUNT_NUMBER>"),
    (re.compile(r"(?i)bearer\s+[A-Za-z0-9._\-]{20,}"), "Bearer <TOKEN>"),
    (re.compile(r"(?i)(password|passwd|pwd)\s*[=:]\s*\S+"), r"\1=<REDACTED>"),
    (re.compile(r"(?i)api[_\-.]?key\s*[=:]\s*\S+"), "api_key=<REDACTED>"),
    (re.compile(r"(?i)secret\s*[=:]\s*\S+"), "secret=<REDACTED>"),
    (re.compile(r"(?i)Authorization:\s*\S+"), "Authorization: <REDACTED>"),
    # Credit card numbers (Luhn-pattern)
    (re.compile(r"\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\b"), "<CARD_NUMBER>"),
    # SSN
    (re.compile(r"\b\d{3}-\d{2}-\d{4}\b"), "<SSN>"),
]

_presidio_available = False
_analyzer = None
_anonymizer = None


def _try_load_presidio() -> bool:
    global _presidio_available, _analyzer, _anonymizer
    try:
        from presidio_analyzer import AnalyzerEngine
        from presidio_anonymizer import AnonymizerEngine
        _analyzer = AnalyzerEngine()
        _anonymizer = AnonymizerEngine()
        _presidio_available = True
        logger.info("Presidio PII engine loaded.")
        return True
    except ImportError:
        logger.warning(
            "presidio-analyzer not installed — using regex-only PII masking. "
            "Run: pip install presidio-analyzer presidio-anonymizer"
        )
        return False


class PIIMasker:
    """
    Two-stage masking:
      1. Financial regex patterns (fast, deterministic)
      2. Presidio NLP engine (general PII: names, emails, phones, etc.)
    Falls back gracefully to regex-only if Presidio is not installed.
    """

    def __init__(self):
        _try_load_presidio()

    def mask(self, text: str) -> str:
        if not text:
            return text

        # Stage 1: financial patterns
        for pattern, replacement in _FINANCIAL_PATTERNS:
            text = pattern.sub(replacement, text)

        # Stage 2: Presidio (if available)
        if _presidio_available and _analyzer and _anonymizer:
            try:
                results = _analyzer.analyze(text=text, language="en")
                if results:
                    anonymized = _anonymizer.anonymize(text=text, analyzer_results=results)
                    text = anonymized.text
            except Exception as exc:
                logger.warning("Presidio masking error (continuing): %s", exc)

        return text

    def mask_dict(self, data: dict) -> dict:
        """Recursively mask all string values in a dict."""
        result = {}
        for k, v in data.items():
            if isinstance(v, str):
                result[k] = self.mask(v)
            elif isinstance(v, dict):
                result[k] = self.mask_dict(v)
            elif isinstance(v, list):
                result[k] = [
                    self.mask(item) if isinstance(item, str)
                    else (self.mask_dict(item) if isinstance(item, dict) else item)
                    for item in v
                ]
            else:
                result[k] = v
        return result
