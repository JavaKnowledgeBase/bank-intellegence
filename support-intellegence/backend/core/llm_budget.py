"""
Token budget controller — enforces daily per-agent LLM spend limits.
Prevents runaway costs during alert storms.
"""
from __future__ import annotations

import logging
from datetime import date

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)


class BudgetExhaustedException(Exception):
    pass


class LLMBudgetController:
    def __init__(self, redis_client: aioredis.Redis, budgets: dict[str, int]):
        self._redis = redis_client
        self._budgets = budgets  # {agent_type: daily_token_limit}

    def _key(self, agent_type: str) -> str:
        return f"csip:budget:{agent_type}:{date.today().isoformat()}"

    async def request_budget(self, agent_type: str, estimated_tokens: int) -> bool:
        """
        Returns True when budget is available.
        Raises BudgetExhaustedException when the daily limit would be exceeded.
        """
        budget = self._budgets.get(agent_type, 100_000)
        key = self._key(agent_type)

        used = int(await self._redis.get(key) or 0)
        if used + estimated_tokens > budget:
            logger.warning(
                "LLM budget exhausted for %s: used=%d budget=%d requested=%d",
                agent_type, used, budget, estimated_tokens,
            )
            raise BudgetExhaustedException(
                f"Daily token budget exhausted for agent '{agent_type}' "
                f"({used}/{budget} tokens used)"
            )

        # Reserve the tokens
        await self._redis.incrby(key, estimated_tokens)
        await self._redis.expire(key, 86400 * 2)
        return True

    async def record_actual_usage(
        self,
        agent_type: str,
        actual_tokens: int,
        estimated_tokens: int,
    ) -> None:
        """
        Correct the reservation once the actual usage is known.
        If actual < estimated we release the difference; if actual > estimated
        we add the overage (best-effort — race conditions are acceptable here).
        """
        delta = actual_tokens - estimated_tokens
        if delta == 0:
            return
        key = self._key(agent_type)
        await self._redis.incrby(key, delta)

    async def get_usage(self, agent_type: str) -> dict:
        key = self._key(agent_type)
        used = int(await self._redis.get(key) or 0)
        budget = self._budgets.get(agent_type, 100_000)
        return {
            "agent_type": agent_type,
            "used": used,
            "budget": budget,
            "remaining": max(0, budget - used),
            "pct_used": round(used / budget * 100, 1) if budget else 0,
        }

    async def get_all_usage(self) -> list[dict]:
        return [await self.get_usage(a) for a in self._budgets]
