"""
Kafka health event processor.
"""
from __future__ import annotations
import logging

logger = logging.getLogger(__name__)


class HealthProcessor:
    def __init__(self, orchestrator):
        self._orchestrator = orchestrator

    async def process(self, event: dict) -> None:
        if not isinstance(event, dict):
            return
        await self._orchestrator.handle_health_event(event)


class DeploymentProcessor:
    def __init__(self, orchestrator):
        self._orchestrator = orchestrator

    async def process(self, event: dict) -> None:
        if not isinstance(event, dict):
            return
        await self._orchestrator.handle_deployment_event(event)
