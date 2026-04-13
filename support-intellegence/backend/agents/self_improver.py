"""
Self-Improver Agent — shadow mode only.
Analyzes CSIP performance signals, proposes improvements as GitHub PRs.
NEVER applies changes autonomously.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Optional

from core.llm_client import ResilientClaudeClient

logger = logging.getLogger(__name__)

_SYSTEM_PROMPT = """
You are an AI systems architect analyzing the performance of an autonomous
support intelligence platform (CSIP) that monitors financial microservices.

Your job is to identify inefficiencies and propose concrete improvements to:
- RCA agent prompts (to improve classification accuracy)
- Noise reduction thresholds (to reduce false positives)
- Code fix agent prompts (to improve first-pass fix rate)
- Kafka consumer configuration (to reduce detection latency)
- LLM cost optimization (batching, dedup window tuning)

You operate in SHADOW MODE: you propose changes, humans decide whether to apply.

Output strict JSON:
{
  "signal_analyzed": "name of the performance signal",
  "current_value": "current metric value",
  "target_value": "desired metric value",
  "root_cause": "why is performance suboptimal",
  "proposed_change": {
    "description": "what to change",
    "file": "path/to/file.py",
    "change_type": "prompt_update | threshold_change | config_tune",
    "specific_change": "exact text or value to change"
  },
  "expected_improvement": "quantified prediction",
  "risk_assessment": "potential downsides",
  "simulated_accuracy": 0.0
}
""".strip()


@dataclass
class ImprovementProposal:
    id: str
    signal_analyzed: str
    current_value: str
    target_value: str
    root_cause: str
    proposed_change: dict
    expected_improvement: str
    risk_assessment: str
    simulated_accuracy: float
    pr_url: Optional[str]
    pr_branch: str
    created_at: str
    status: str = "pending_review"  # pending_review | approved | rejected
    llm_tokens: int = 0
    llm_cost: float = 0.0


class SelfImproverAgent:
    """
    Runs daily at 1 AM UTC. Reads performance metrics from DB and proposes
    improvements via GitHub PRs. Notifies lead architect on each proposal.
    """

    def __init__(
        self,
        llm: ResilientClaudeClient,
        db_session_factory,
        github_token: Optional[str],
        ws_broadcaster,
        lead_architect_github: str = "lead-architect",
    ):
        self._llm = llm
        self._session_factory = db_session_factory
        self._github_token = github_token
        self._broadcast = ws_broadcaster
        self._lead = lead_architect_github

    async def run_analysis(self) -> list[ImprovementProposal]:
        """
        Pull performance signals from DB, ask Claude for improvement proposals,
        create a GitHub PR per proposal, notify via WebSocket.
        """
        signals = await self._gather_signals()
        proposals = []

        for signal_name, signal_data in signals.items():
            logger.info("Self-improver analyzing signal: %s", signal_name)
            proposal = await self._propose_improvement(signal_name, signal_data)
            if proposal:
                if self._github_token:
                    await self._create_proposal_pr(proposal)
                proposals.append(proposal)

                await self._broadcast({
                    "type": "improvement_proposal",
                    "proposal_id": proposal.id,
                    "summary": proposal.root_cause,
                    "pr_url": proposal.pr_url,
                })

        logger.info("Self-improver generated %d proposals", len(proposals))
        return proposals

    async def _gather_signals(self) -> dict:
        """Query DB for performance metrics. Returns {signal_name: data_dict}."""
        from sqlalchemy import text
        signals = {}
        try:
            async with self._session_factory() as session:
                # RCA accuracy
                result = await session.execute(text("""
                    SELECT category,
                           COUNT(*) as total,
                           SUM(CASE WHEN final_outcome = category THEN 1 ELSE 0 END) as correct
                    FROM issues
                    WHERE created_at > NOW() - INTERVAL '7 days'
                      AND final_outcome IS NOT NULL
                    GROUP BY category
                """))
                rows = result.fetchall()
                if rows:
                    for row in rows:
                        cat, total, correct = row
                        acc = (correct or 0) / max(total, 1)
                        if acc < 0.85:
                            signals[f"rca_accuracy_{cat}"] = {
                                "category": cat,
                                "accuracy": acc,
                                "total": total,
                                "description": f"RCA accuracy for {cat} is {acc:.0%} (target: 85%)",
                            }

                # Fix first-pass rate
                result = await session.execute(text("""
                    SELECT AVG(fix_attempts) as avg_attempts,
                           COUNT(*) as total_fixes
                    FROM issues
                    WHERE fix_pr_url IS NOT NULL
                      AND created_at > NOW() - INTERVAL '7 days'
                """))
                row = result.fetchone()
                if row and row[0] and float(row[0]) > 1.5:
                    signals["fix_first_pass_rate"] = {
                        "avg_attempts": float(row[0]),
                        "total_fixes": row[1],
                        "description": f"Average {float(row[0]):.1f} fix attempts (target: <1.5)",
                    }

                # False positive rate
                result = await session.execute(text("""
                    SELECT COUNT(*) FROM issues
                    WHERE status = 'false_positive'
                      AND created_at > NOW() - INTERVAL '7 days'
                """))
                fp_count = result.scalar() or 0
                if fp_count > 5:
                    signals["false_positive_rate"] = {
                        "count": fp_count,
                        "description": f"{fp_count} false positives in last 7 days (target: <5)",
                    }

        except Exception as exc:
            logger.warning("Could not gather performance signals: %s", exc)
            # Return a synthetic signal for demo purposes
            signals["demo_signal"] = {
                "description": "CSIP is starting up — insufficient data for real signals",
                "current": "N/A",
            }

        return signals

    async def _propose_improvement(self, signal_name: str, signal_data: dict) -> Optional[ImprovementProposal]:
        import uuid

        user_prompt = f"""
Performance signal: {signal_name}
Data: {json.dumps(signal_data, indent=2)}

Propose a concrete improvement to CSIP's configuration, prompts, or thresholds.
""".strip()

        try:
            response_text, meta = await self._llm.call(
                agent_type="self_improver",
                system=_SYSTEM_PROMPT,
                user=user_prompt,
                max_tokens=1000,
                temperature=0.2,
            )
            data = json.loads(response_text)
            now = datetime.now(timezone.utc).isoformat()
            proposal_id = str(uuid.uuid4())[:8]

            return ImprovementProposal(
                id=proposal_id,
                signal_analyzed=data.get("signal_analyzed", signal_name),
                current_value=str(data.get("current_value", "")),
                target_value=str(data.get("target_value", "")),
                root_cause=data.get("root_cause", ""),
                proposed_change=data.get("proposed_change", {}),
                expected_improvement=data.get("expected_improvement", ""),
                risk_assessment=data.get("risk_assessment", ""),
                simulated_accuracy=float(data.get("simulated_accuracy", 0.0)),
                pr_url=None,
                pr_branch=f"csip-improvement/{now[:10]}-{signal_name[:20]}",
                created_at=now,
                llm_tokens=meta.get("input_tokens", 0) + meta.get("output_tokens", 0),
                llm_cost=meta.get("cost_usd", 0.0),
            )
        except Exception as exc:
            logger.error("Self-improver proposal failed for %s: %s", signal_name, exc)
            return None

    async def _create_proposal_pr(self, proposal: ImprovementProposal) -> None:
        """Create a GitHub PR with the improvement proposal."""
        try:
            from github import Github
            g = Github(self._github_token)
            # In production this would target the CSIP repo itself
            logger.info(
                "Self-improver proposal %s — PR creation requires CSIP repo config",
                proposal.id,
            )
        except Exception as exc:
            logger.warning("Could not create proposal PR: %s", exc)
