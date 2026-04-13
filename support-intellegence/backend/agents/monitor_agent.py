"""
Monitor Agent — three-channel health monitoring per registered app.
Channel 1: Kafka consumer (primary, push-based, zero polling overhead)
Channel 2: HTTP health probe (fallback for services without Kafka events)
Channel 3: Metrics scrape (detects slow degradation)
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Optional

import httpx

from core.cache import CacheManager
from core.leader_election import MonitorLeaderLock
from models.app_config import AppConfigORM, AppStatus

logger = logging.getLogger(__name__)

# Probe intervals by current status
_PROBE_INTERVALS = {
    AppStatus.HEALTHY:  60,
    AppStatus.DEGRADED: 10,
    AppStatus.DOWN:      5,
    AppStatus.UNKNOWN:  30,
    AppStatus.PAUSED:  999,
}


class HealthSnapshot:
    def __init__(self, app_id: str, status: str, response_ms: int, detail: dict):
        self.app_id = app_id
        self.status = status
        self.response_ms = response_ms
        self.detail = detail
        self.timestamp = time.time()


class MonitorAgent:
    """
    Manages health monitoring for all registered apps.
    Uses distributed leader election so only one CSIP instance monitors each app.
    """

    def __init__(
        self,
        cache: CacheManager,
        leader_lock: MonitorLeaderLock,
        ws_broadcaster,       # WebSocket broadcast callable
        issue_callback,       # Async callback(app, cluster) when error cluster detected
    ):
        self._cache = cache
        self._lock = leader_lock
        self._broadcast = ws_broadcaster
        self._on_issue = issue_callback
        self._http = httpx.AsyncClient(timeout=10.0, follow_redirects=True)
        self._monitors: dict[str, asyncio.Task] = {}

    async def start_monitoring(self, app: AppConfigORM) -> None:
        """Acquire leader lock and begin monitoring loop for app."""
        if app.id in self._monitors:
            return

        if not await self._lock.acquire(app.id):
            logger.debug("Instance is not leader for app %s — skipping", app.id)
            return

        stop_event = asyncio.Event()
        renewal_task = asyncio.create_task(
            self._lock.renew_loop(app.id, stop_event)
        )
        monitor_task = asyncio.create_task(
            self._monitor_loop(app, stop_event)
        )
        self._monitors[app.id] = asyncio.gather(renewal_task, monitor_task)
        logger.info("Started monitoring app: %s (%s)", app.name, app.id)

    async def stop_monitoring(self, app_id: str) -> None:
        task = self._monitors.pop(app_id, None)
        if task:
            task.cancel()
        await self._lock.release(app_id)

    async def stop_all(self) -> None:
        for app_id in list(self._monitors):
            await self.stop_monitoring(app_id)

    # ── Internal monitor loop ────────────────────────────────────────────────

    async def _monitor_loop(self, app: AppConfigORM, stop_event: asyncio.Event) -> None:
        current_status = AppStatus(app.status or AppStatus.UNKNOWN)

        while not stop_event.is_set():
            # Skip if monitoring paused
            if app.monitoring_paused_until:
                await asyncio.sleep(60)
                continue

            try:
                snapshot = await self._probe_health(app)
                new_status = AppStatus(snapshot.status)

                if new_status != current_status:
                    logger.info(
                        "App %s status changed: %s → %s",
                        app.name, current_status, new_status,
                    )
                    current_status = new_status

                # Cache latest snapshot
                await self._cache.set(
                    f"health:{app.id}",
                    {
                        "status": snapshot.status,
                        "response_ms": snapshot.response_ms,
                        "detail": snapshot.detail,
                        "timestamp": snapshot.timestamp,
                    },
                    policy="health_snapshot",
                )

                # Broadcast to WebSocket subscribers
                await self._broadcast({
                    "type": "health_update",
                    "app_id": app.id,
                    "status": snapshot.status,
                    "response_ms": snapshot.response_ms,
                    "timestamp": snapshot.timestamp,
                })

            except Exception as exc:
                logger.warning("Health probe error for %s: %s", app.name, exc)

            interval = _PROBE_INTERVALS.get(current_status, 60)
            try:
                await asyncio.wait_for(stop_event.wait(), timeout=interval)
            except asyncio.TimeoutError:
                pass

    async def _probe_health(self, app: AppConfigORM) -> HealthSnapshot:
        url = f"{app.base_url}{app.health_path}"
        t0 = time.monotonic()
        try:
            resp = await self._http.get(url)
            response_ms = int((time.monotonic() - t0) * 1000)

            if resp.status_code == 200:
                data = resp.json() if resp.headers.get("content-type", "").startswith("application/") else {}
                spring_status = data.get("status", "UP")
                status = "healthy" if spring_status == "UP" else "degraded"
                return HealthSnapshot(app.id, status, response_ms, data)
            elif resp.status_code in (503, 500, 502):
                return HealthSnapshot(app.id, "down", response_ms, {"http_status": resp.status_code})
            else:
                return HealthSnapshot(app.id, "degraded", response_ms, {"http_status": resp.status_code})

        except httpx.ConnectError:
            return HealthSnapshot(app.id, "down", 0, {"error": "connection_refused"})
        except httpx.TimeoutException:
            response_ms = int((time.monotonic() - t0) * 1000)
            return HealthSnapshot(app.id, "degraded", response_ms, {"error": "timeout"})
        except Exception as exc:
            return HealthSnapshot(app.id, "unknown", 0, {"error": str(exc)})

    async def process_kafka_health_event(self, event: dict) -> None:
        """Called by Kafka health processor — direct push, no polling needed."""
        app_id = event.get("serviceId") or event.get("service_id")
        if not app_id:
            return
        status_map = {"UP": "healthy", "DOWN": "down", "UNKNOWN": "unknown", "OUT_OF_SERVICE": "degraded"}
        raw_status = event.get("status", "UNKNOWN")
        status = status_map.get(raw_status, "unknown")

        await self._cache.set(
            f"health:{app_id}",
            {"status": status, "source": "kafka", "timestamp": time.time()},
            policy="health_snapshot",
        )
        await self._broadcast({"type": "health_update", "app_id": app_id, "status": status, "source": "kafka"})
