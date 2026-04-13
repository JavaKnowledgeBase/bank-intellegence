"""
Root Cause Analysis Agent — two-stage classification.
Stage 1: fast rule-based (40% of issues, ~0ms)
Stage 2: Claude LLM (remaining 60%, ~3–8s)
"""
from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Optional

from core.llm_client import ResilientClaudeClient
from core.vector_store import VectorStore

logger = logging.getLogger(__name__)


# ── Fast rules ────────────────────────────────────────────────────────────────

@dataclass
class FastRule:
    pattern: re.Pattern
    category: str
    severity: str
    confidence: float
    title_template: str


_FAST_RULES: list[FastRule] = [
    FastRule(re.compile(r"Connection refused|ECONNREFUSED|connect ECONNREFUSED"),
             "infrastructure", "p1", 0.95, "Connection refused — downstream unreachable"),
    FastRule(re.compile(r"OOMKilled|OutOfMemoryError|java\.lang\.OutOfMemoryError"),
             "infrastructure", "p1", 0.95, "Out of memory — pod needs resource increase"),
    FastRule(re.compile(r"Configuration property.*not found|application\.properties.*missing"),
             "configuration", "p2", 0.90, "Missing configuration property"),
    FastRule(re.compile(r"@ConfigurationProperties.*binding|Binding.*to target.*failed"),
             "configuration", "p2", 0.90, "Spring configuration binding failure"),
    FastRule(re.compile(r"Could not obtain JDBC Connection|HikariPool.*Connection is not available"),
             "infrastructure", "p1", 0.92, "Database connection pool exhausted"),
    FastRule(re.compile(r"Kafka.*TimeoutException|consumer.*lag.*exceed"),
             "infrastructure", "p2", 0.88, "Kafka consumer lag / timeout"),
    FastRule(re.compile(r"401 Unauthorized|JWT expired|token.*expired"),
             "configuration", "p2", 0.85, "Authentication token expired"),
    FastRule(re.compile(r"503 Service Unavailable|upstream connect error"),
             "infrastructure", "p1", 0.90, "Upstream service unavailable"),
    FastRule(re.compile(r"NullPointerException.*at com\.(?:jpmc|cibap)"),
             "code", "p2", 0.72, "NullPointerException in application code"),
    FastRule(re.compile(r"StackOverflowError.*at com\.(?:jpmc|cibap)"),
             "code", "p1", 0.75, "StackOverflowError in application code"),
    FastRule(re.compile(r"ClassCastException.*at com\.(?:jpmc|cibap)"),
             "code", "p2", 0.70, "ClassCastException in application code"),
]


# ── RCA prompt ────────────────────────────────────────────────────────────────

_SYSTEM_PROMPT = """
You are a Principal Site Reliability Engineer at a major financial institution.
You specialize in diagnosing incidents in Java Spring Boot microservices running
on Kubernetes within an Apache Kafka event-driven architecture.

You are precise, evidence-based, and cautious. You NEVER speculate beyond what
the evidence supports. When confidence is below 0.85, say so explicitly.

For financial services applications, additionally consider:
- Kafka consumer lag as a proxy for service health
- Database connection pool exhaustion under load
- JWT/OAuth2 token expiry cascades
- Downstream payment processor timeouts

Required output — strict JSON, no other text:
{
  "category": "infrastructure" | "configuration" | "code" | "unknown",
  "severity": "p0" | "p1" | "p2" | "p3",
  "confidence": 0.0-1.0,
  "title": "short descriptive issue title",
  "root_cause_summary": "2-3 sentences max",
  "technical_detail": "precise technical explanation with evidence",
  "affected_file": "path/to/File.java or null",
  "affected_class": "ClassName or null",
  "affected_method": "methodName(params) or null",
  "recommended_action": "specific, actionable next step",
  "estimated_user_impact": "concise impact description",
  "supporting_evidence": ["evidence point 1", "evidence point 2"],
  "ruled_out": ["hypothesis and why ruled out"],
  "needs_more_context": ["what additional info would increase confidence"]
}
""".strip()


@dataclass
class RCAResult:
    category: str
    severity: str
    confidence: float
    title: str
    root_cause_summary: str
    technical_detail: str
    affected_file: Optional[str] = None
    affected_class: Optional[str] = None
    affected_method: Optional[str] = None
    recommended_action: str = ""
    estimated_user_impact: str = ""
    supporting_evidence: list[str] = field(default_factory=list)
    ruled_out: list[str] = field(default_factory=list)
    needs_more_context: list[str] = field(default_factory=list)
    classification_method: str = "unknown"
    llm_tokens_used: int = 0
    llm_cost_usd: float = 0.0


class RCAAgent:
    def __init__(self, llm: ResilientClaudeClient, vector_store: VectorStore):
        self._llm = llm
        self._vs = vector_store

    async def classify(
        self,
        service: str,
        error_message: str,
        stack_trace: Optional[str],
        recent_deployments: list[dict],
        k8s_context: Optional[dict] = None,
    ) -> RCAResult:
        """Run fast-rule classification; fall back to Claude when needed."""

        combined = f"{error_message}\n{stack_trace or ''}"

        # Stage 1: fast rules
        for rule in _FAST_RULES:
            if rule.pattern.search(combined):
                if rule.confidence >= 0.85:
                    logger.info(
                        "Fast rule matched [%s/%s] for %s (conf=%.2f)",
                        rule.category, rule.severity, service, rule.confidence,
                    )
                    return RCAResult(
                        category=rule.category,
                        severity=rule.severity,
                        confidence=rule.confidence,
                        title=rule.title_template,
                        root_cause_summary=f"Pattern match: {rule.title_template}",
                        technical_detail=f"Matched rule pattern on error: {error_message[:300]}",
                        classification_method="fast_rule",
                    )

        # Stage 2: similar historical issues from vector store
        similar = await self._vs.query(combined[:500], top_k=3)
        similar_context = ""
        if similar:
            similar_context = "\n\nSimilar past issues:\n" + "\n".join(
                f"- [{h['score']:.2f}] {h.get('metadata', {}).get('title', 'unknown')}: "
                f"{h.get('metadata', {}).get('category', '')} / {h.get('metadata', {}).get('severity', '')}"
                for h in similar
            )

        # Stage 2: Claude LLM
        user_prompt = f"""
Service: {service}

Error message:
{error_message[:1000]}

Stack trace:
{(stack_trace or 'not available')[:2000]}

Recent deployments (last 24h):
{json.dumps(recent_deployments[:5], indent=2) if recent_deployments else 'none'}

K8s context:
{json.dumps(k8s_context or {}, indent=2)}
{similar_context}

Classify this incident.
""".strip()

        try:
            response_text, meta = await self._llm.call(
                agent_type="rca_agent",
                system=_SYSTEM_PROMPT,
                user=user_prompt,
                max_tokens=1200,
                temperature=0.1,
            )
            result = json.loads(response_text)
            return RCAResult(
                category=result.get("category", "unknown"),
                severity=result.get("severity", "p2"),
                confidence=float(result.get("confidence", 0.5)),
                title=result.get("title", "Unknown issue"),
                root_cause_summary=result.get("root_cause_summary", ""),
                technical_detail=result.get("technical_detail", ""),
                affected_file=result.get("affected_file"),
                affected_class=result.get("affected_class"),
                affected_method=result.get("affected_method"),
                recommended_action=result.get("recommended_action", ""),
                estimated_user_impact=result.get("estimated_user_impact", ""),
                supporting_evidence=result.get("supporting_evidence", []),
                ruled_out=result.get("ruled_out", []),
                needs_more_context=result.get("needs_more_context", []),
                classification_method="llm_claude",
                llm_tokens_used=meta.get("input_tokens", 0) + meta.get("output_tokens", 0),
                llm_cost_usd=meta.get("cost_usd", 0.0),
            )
        except json.JSONDecodeError as exc:
            logger.warning("Claude RCA response was not valid JSON: %s", exc)
            return RCAResult(
                category="unknown",
                severity="p2",
                confidence=0.3,
                title="Classification failed — manual investigation needed",
                root_cause_summary="LLM response parse error",
                technical_detail=response_text[:500],
                classification_method="llm_claude",
            )
        except Exception as exc:
            logger.error("RCA agent error: %s", exc)
            return RCAResult(
                category="unknown",
                severity="p2",
                confidence=0.0,
                title="Classification unavailable",
                root_cause_summary=str(exc),
                technical_detail="",
                classification_method="error",
            )
