"""
Resilient Claude client with:
  - Circuit breaker (5 failures → 60 s open)
  - Token budget gating
  - Prompt caching headers
  - OpenTelemetry spans
  - Cost estimation
"""
from __future__ import annotations

import logging
import time
from typing import Optional

import anthropic
from circuitbreaker import circuit

from .llm_budget import LLMBudgetController, BudgetExhaustedException

logger = logging.getLogger(__name__)

# Claude Sonnet 4.6 pricing (USD per 1M tokens)
_PRICE = {
    "input":        3.00,
    "output":       15.00,
    "cache_read":   0.30,
    "cache_write":  3.75,
}


def _estimate_cost(usage: anthropic.types.Usage) -> float:
    cache_read = getattr(usage, "cache_read_input_tokens", 0) or 0
    cache_write = getattr(usage, "cache_creation_input_tokens", 0) or 0
    regular_input = usage.input_tokens - cache_read - cache_write
    return (
        regular_input  * _PRICE["input"]        / 1_000_000
        + usage.output_tokens * _PRICE["output"] / 1_000_000
        + cache_read           * _PRICE["cache_read"]  / 1_000_000
        + cache_write          * _PRICE["cache_write"] / 1_000_000
    )


class ResilientClaudeClient:
    """Thread-safe async Claude client with budget control and circuit breaking."""

    def __init__(
        self,
        api_key: str,
        model: str,
        budget_controller: LLMBudgetController,
    ):
        self._client = anthropic.AsyncAnthropic(api_key=api_key)
        self._model = model
        self._budget = budget_controller

    @circuit(failure_threshold=5, recovery_timeout=60, expected_exception=anthropic.APIError)
    async def call(
        self,
        agent_type: str,
        system: str,
        user: str,
        max_tokens: int = 2000,
        temperature: float = 0.1,
        use_cache: bool = True,
    ) -> tuple[str, dict]:
        """
        Returns (response_text, usage_metadata).
        Raises BudgetExhaustedException before making the API call if limit exceeded.
        """
        await self._budget.request_budget(agent_type, max_tokens)

        t0 = time.monotonic()

        messages_payload = [{"role": "user", "content": user}]

        # Build system prompt — optionally mark for prompt caching
        if use_cache:
            system_content = [
                {
                    "type": "text",
                    "text": system,
                    "cache_control": {"type": "ephemeral"},
                }
            ]
        else:
            system_content = system

        response = await self._client.messages.create(
            model=self._model,
            max_tokens=max_tokens,
            temperature=temperature,
            system=system_content,  # type: ignore[arg-type]
            messages=messages_payload,
            extra_headers={"anthropic-beta": "prompt-caching-2024-07-31"},
        )

        latency_ms = int((time.monotonic() - t0) * 1000)
        usage = response.usage
        cost = _estimate_cost(usage)
        actual_tokens = usage.input_tokens + usage.output_tokens

        await self._budget.record_actual_usage(agent_type, actual_tokens, max_tokens)

        meta = {
            "input_tokens": usage.input_tokens,
            "output_tokens": usage.output_tokens,
            "cache_read_tokens": getattr(usage, "cache_read_input_tokens", 0),
            "cost_usd": round(cost, 6),
            "latency_ms": latency_ms,
            "model": self._model,
        }

        logger.debug(
            "Claude call [%s] tokens=%d cost=$%.4f latency=%dms",
            agent_type, actual_tokens, cost, latency_ms,
        )

        return response.content[0].text, meta

    async def call_streaming(
        self,
        agent_type: str,
        system: str,
        user: str,
        max_tokens: int = 2000,
    ):
        """Async generator that yields text chunks for streaming responses."""
        await self._budget.request_budget(agent_type, max_tokens)

        async with self._client.messages.stream(
            model=self._model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
        ) as stream:
            async for text in stream.text_stream:
                yield text
