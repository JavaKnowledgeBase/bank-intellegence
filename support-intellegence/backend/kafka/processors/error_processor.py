"""
Kafka error event processor.
Validates, PII-masks, and forwards to Orchestrator.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


class ErrorProcessor:
    def __init__(self, orchestrator):
        self._orchestrator = orchestrator

    async def process(self, event: dict) -> None:
        """Process a single error event from cibap.errors topic."""
        if not isinstance(event, dict):
            return

        # Require minimum fields
        if not event.get("service") and not event.get("message"):
            logger.debug("Skipping malformed error event: %s", list(event.keys()))
            return

        # Normalise field names (different CIBAP services use different schemas)
        normalised = {
            "service":    event.get("service") or event.get("serviceName") or "unknown",
            "level":      (event.get("level") or event.get("severity") or "ERROR").upper(),
            "message":    event.get("message") or event.get("msg") or "",
            "stackTrace": event.get("stackTrace") or event.get("stack_trace") or event.get("exception"),
            "logger":     event.get("logger") or event.get("logger_name"),
            "timestamp":  event.get("timestamp") or event.get("@timestamp"),
            "correlationId": event.get("correlationId") or event.get("traceId"),
        }

        await self._orchestrator.handle_error_event(normalised)
