"""
CSIP Kafka producer — publishes to csip.issues, csip.fix-status, csip.alerts.
"""
from __future__ import annotations

import json
import logging
from typing import Optional

logger = logging.getLogger(__name__)

PRODUCED_TOPICS = {
    "csip.issues":     "Issue detected/updated events",
    "csip.fix-status": "Fix pipeline status updates",
    "csip.alerts":     "High-severity alerts for downstream consumers",
}


class CSIPKafkaProducer:
    def __init__(self, bootstrap_servers: str):
        self._bootstrap = bootstrap_servers
        self._producer = None

    async def start(self) -> None:
        try:
            from aiokafka import AIOKafkaProducer
            self._producer = AIOKafkaProducer(
                bootstrap_servers=self._bootstrap,
                value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
                acks="all",
                retries=3,
            )
            await self._producer.start()
            logger.info("Kafka producer started")
        except ImportError:
            logger.warning("aiokafka not installed — Kafka producer disabled")
        except Exception as exc:
            logger.warning("Kafka producer start failed: %s", exc)

    async def stop(self) -> None:
        if self._producer:
            await self._producer.stop()

    async def publish_issue(self, issue_data: dict) -> None:
        await self._send("csip.issues", issue_data)

    async def publish_fix_status(self, status_data: dict) -> None:
        await self._send("csip.fix-status", status_data)

    async def publish_alert(self, alert_data: dict) -> None:
        await self._send("csip.alerts", alert_data)

    async def _send(self, topic: str, payload: dict) -> None:
        if not self._producer:
            return
        try:
            await self._producer.send_and_wait(topic, payload)
        except Exception as exc:
            logger.warning("Failed to publish to %s: %s", topic, exc)
