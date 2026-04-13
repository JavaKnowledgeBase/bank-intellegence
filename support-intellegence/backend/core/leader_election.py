"""
Redis-based distributed leader election for CSIP Monitor Agent.
Only the leader instance polls health / streams logs for a given app.
"""
from __future__ import annotations

import asyncio
import logging
import uuid

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

_LOCK_TTL = 30      # seconds — must renew within this window or lock expires
_RENEWAL_INTERVAL = 10  # renew every 10 s

# Lua scripts — atomic compare-and-swap operations
_RENEW_SCRIPT = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("expire", KEYS[1], ARGV[2])
else
    return 0
end
"""

_RELEASE_SCRIPT = """
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
"""


class MonitorLeaderLock:
    """
    Acquire, hold (with periodic renewal), and release a named distributed lock.
    The lock is identified by app_id; only one CSIP instance holds it at a time.
    """

    def __init__(self, redis_client: aioredis.Redis, instance_id: str | None = None):
        self._redis = redis_client
        self.instance_id = instance_id or str(uuid.uuid4())

    def _key(self, app_id: str) -> str:
        return f"csip:leader:{app_id}"

    async def acquire(self, app_id: str) -> bool:
        """Try to acquire leader lock for app_id. Returns True on success."""
        acquired = await self._redis.set(
            self._key(app_id),
            self.instance_id,
            ex=_LOCK_TTL,
            nx=True,
        )
        if acquired:
            logger.debug("Instance %s acquired leader lock for app %s", self.instance_id, app_id)
        return bool(acquired)

    async def renew_loop(self, app_id: str, stop_event: asyncio.Event) -> None:
        """
        Background coroutine: renews the lock every RENEWAL_INTERVAL seconds.
        Signals stop_event if the lock is lost (e.g. instance was paused too long).
        """
        key = self._key(app_id)
        while not stop_event.is_set():
            await asyncio.sleep(_RENEWAL_INTERVAL)
            try:
                renewed = await self._redis.eval(
                    _RENEW_SCRIPT, 1, key, self.instance_id, str(_LOCK_TTL)
                )
                if not renewed:
                    logger.warning(
                        "Instance %s lost leader lock for app %s — stopping monitor.",
                        self.instance_id, app_id,
                    )
                    stop_event.set()
                    return
            except Exception as exc:
                logger.error("Lock renewal error for app %s: %s", app_id, exc)

    async def release(self, app_id: str) -> None:
        """Release the lock if we still hold it."""
        key = self._key(app_id)
        await self._redis.eval(_RELEASE_SCRIPT, 1, key, self.instance_id)
        logger.debug("Instance %s released leader lock for app %s", self.instance_id, app_id)

    async def is_leader(self, app_id: str) -> bool:
        val = await self._redis.get(self._key(app_id))
        return val == self.instance_id or val == self.instance_id.encode()
