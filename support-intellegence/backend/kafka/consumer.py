"""
CSIP Kafka consumer — subscribes to CIBAP error, health, and deployment topics.
Manual commit for exactly-once processing semantics.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Callable, Awaitable

logger = logging.getLogger(__name__)

CONSUMED_TOPICS = {
    "cibap.errors":      "handle_error_event",
    "cibap.health":      "handle_health_event",
    "cibap.deployments": "handle_deployment_event",
}


class CSIPKafkaConsumer:
    def __init__(self, bootstrap_servers: str, orchestrator):
        self._bootstrap = bootstrap_servers
        self._orchestrator = orchestrator
        self._running = False

    async def start(self) -> None:
        try:
            from aiokafka import AIOKafkaConsumer
        except ImportError:
            logger.warning("aiokafka not installed — Kafka consumer disabled. Install with: pip install aiokafka")
            return

        consumer = AIOKafkaConsumer(
            *CONSUMED_TOPICS.keys(),
            bootstrap_servers=self._bootstrap,
            group_id="csip-monitoring",
            value_deserializer=lambda m: json.loads(m.decode("utf-8")),
            auto_offset_reset="latest",
            enable_auto_commit=False,
            max_poll_records=500,
            fetch_max_wait_ms=500,
        )

        try:
            await consumer.start()
            self._running = True
            logger.info(
                "Kafka consumer started — subscribed to: %s",
                ", ".join(CONSUMED_TOPICS.keys()),
            )

            async for msg in consumer:
                if not self._running:
                    break
                handler_name = CONSUMED_TOPICS.get(msg.topic)
                if handler_name:
                    handler = getattr(self._orchestrator, handler_name, None)
                    if handler:
                        try:
                            await handler(msg.value)
                        except Exception as exc:
                            logger.error(
                                "Error handling Kafka message [%s]: %s", msg.topic, exc
                            )
                await consumer.commit()

        except asyncio.CancelledError:
            logger.info("Kafka consumer shutting down")
        except Exception as exc:
            logger.error("Kafka consumer fatal error: %s", exc)
        finally:
            await consumer.stop()

    async def stop(self) -> None:
        self._running = False
