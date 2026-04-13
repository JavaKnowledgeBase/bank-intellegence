"""
Multi-level cache: L1 in-process TTL cache + L2 Redis cluster.
"""
from __future__ import annotations

import asyncio
import json
import time
import hashlib
import logging
from typing import Any, Optional
from functools import wraps

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

# ── L1: Simple in-process TTL cache ──────────────────────────────────────────

_l1_store: dict[str, tuple[Any, float]] = {}
_L1_TTL = 30  # seconds


def _l1_get(key: str) -> Any | None:
    entry = _l1_store.get(key)
    if entry and time.monotonic() < entry[1]:
        return entry[0]
    _l1_store.pop(key, None)
    return None


def _l1_set(key: str, value: Any, ttl: int = _L1_TTL) -> None:
    _l1_store[key] = (value, time.monotonic() + ttl)


CACHE_POLICIES: dict[str, dict] = {
    "app_config":      {"ttl": 300,   "layer": "redis"},
    "health_snapshot": {"ttl": 15,    "layer": "redis"},
    "rca_result":      {"ttl": 3600,  "layer": "redis"},
    "embedding":       {"ttl": 86400, "layer": "redis"},
    "issue_list":      {"ttl": 30,    "layer": "redis"},
    "audit_recent":    {"ttl": 60,    "layer": "redis"},
}


class CacheManager:
    """Facade for L1 + Redis L2 caching."""

    def __init__(self, redis_client: aioredis.Redis):
        self._redis = redis_client

    # ── Public API ────────────────────────────────────────────────────────────

    async def get(self, key: str, policy: str = "issue_list") -> Any | None:
        # L1 check
        val = _l1_get(key)
        if val is not None:
            return val

        # L2 Redis
        raw = await self._redis.get(key)
        if raw:
            value = json.loads(raw)
            _l1_set(key, value)
            return value
        return None

    async def set(self, key: str, value: Any, policy: str = "issue_list") -> None:
        ttl = CACHE_POLICIES.get(policy, {}).get("ttl", 60)
        serialised = json.dumps(value, default=str)
        await self._redis.setex(key, ttl, serialised)
        _l1_set(key, value, min(ttl, _L1_TTL))

    async def delete(self, key: str) -> None:
        await self._redis.delete(key)
        _l1_store.pop(key, None)

    async def invalidate_prefix(self, prefix: str) -> int:
        """Delete all keys matching prefix:*"""
        cursor = 0
        deleted = 0
        while True:
            cursor, keys = await self._redis.scan(cursor, match=f"{prefix}:*", count=100)
            if keys:
                await self._redis.delete(*keys)
                deleted += len(keys)
                for k in keys:
                    _l1_store.pop(k.decode() if isinstance(k, bytes) else k, None)
            if cursor == 0:
                break
        return deleted

    async def exists(self, key: str) -> bool:
        if _l1_get(key) is not None:
            return True
        return bool(await self._redis.exists(key))

    async def increment(self, key: str, ttl: int = 300) -> int:
        """Atomic increment with TTL (used for error counters)."""
        val = await self._redis.incr(key)
        if val == 1:
            await self._redis.expire(key, ttl)
        return val

    # ── Deduplication helpers ────────────────────────────────────────────────

    @staticmethod
    def error_hash(service: str, error_class: str, message: str) -> str:
        raw = f"{service}:{error_class}:{message[:200]}"
        return hashlib.sha256(raw.encode()).hexdigest()[:16]

    async def seen_error(self, hash_key: str, window: int = 300) -> bool:
        """Returns True if error hash was seen within the dedup window."""
        key = f"csip:dedup:{hash_key}"
        exists = await self._redis.exists(key)
        if not exists:
            await self._redis.setex(key, window, "1")
        return bool(exists)

    async def record_seen(self, hash_key: str, window: int = 300) -> int:
        """Increment occurrence counter for a deduplicated error."""
        key = f"csip:dedup:count:{hash_key}"
        count = await self.increment(key, ttl=window)
        return count

    async def check_health(self) -> bool:
        try:
            await self._redis.ping()
            return True
        except Exception:
            return False
