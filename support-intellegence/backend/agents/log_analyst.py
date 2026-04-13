"""
Log Analyst Agent — aggressive noise reduction before any LLM call.
Reduces 10,000 raw log lines to 3-5 unique error clusters.
"""
from __future__ import annotations

import hashlib
import logging
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Optional

from core.cache import CacheManager
from core.pii_masker import PIIMasker
from core.vector_store import VectorStore

logger = logging.getLogger(__name__)

_NOISE_LEVELS = {"DEBUG", "INFO", "TRACE"}


@dataclass
class ErrorCluster:
    """A deduplicated group of similar error events."""
    cluster_id: str
    service: str
    level: str
    error_class: str
    message: str
    stack_trace: Optional[str]
    count: int
    first_seen: float
    last_seen: float
    raw_hash: str


class LogAnalyst:
    """
    Noise reduction pipeline:
      1. Level filter        — keep only ERROR + WARN
      2. Rate limit          — max 20 unique patterns per service per 5-min window
      3. SHA-256 dedup       — suppress identical errors within window
      4. Semantic dedup      — cosine similarity > 0.88 → merge into cluster
      5. PII masking         — all content masked before LLM/vector store
    """

    def __init__(
        self,
        cache: CacheManager,
        vector_store: VectorStore,
        pii_masker: PIIMasker,
        dedup_window: int = 300,
        max_patterns_per_window: int = 20,
        semantic_threshold: float = 0.88,
    ):
        self._cache = cache
        self._vs = vector_store
        self._masker = pii_masker
        self._dedup_window = dedup_window
        self._max_patterns = max_patterns_per_window
        self._semantic_threshold = semantic_threshold
        self._rate_counters: dict[str, int] = defaultdict(int)

    async def process_batch(self, events: list[dict]) -> list[ErrorCluster]:
        """
        Process a batch of raw log events.
        Returns deduplicated ErrorCluster list ready for RCA.
        """
        clusters: list[ErrorCluster] = []

        for event in events:
            level = event.get("level", "INFO").upper()

            # 1. Level filter
            if level in _NOISE_LEVELS:
                continue

            service = event.get("service", "unknown")
            message = event.get("message", "")
            stack_trace = event.get("stackTrace") or event.get("stack_trace")
            error_class = self._extract_error_class(stack_trace or message)

            # 2. PII masking
            message = self._masker.mask(message)
            if stack_trace:
                stack_trace = self._masker.mask(stack_trace)

            # 3. Rate limit
            rate_key = f"csip:rate:{service}"
            rate = await self._cache.increment(rate_key, ttl=self._dedup_window)
            if rate > self._max_patterns:
                logger.debug("Rate limit hit for service %s — suppressing event", service)
                continue

            # 4. SHA-256 dedup
            raw_hash = self._cache.error_hash(service, error_class, message)
            if await self._cache.seen_error(raw_hash, self._dedup_window):
                count = await self._cache.record_seen(raw_hash, self._dedup_window)
                # Update existing cluster count
                for c in clusters:
                    if c.raw_hash == raw_hash:
                        c.count = count
                        c.last_seen = time.time()
                continue

            # 5. Semantic dedup via vector store
            combined_text = f"{service} {error_class} {message}"
            similar = await self._vs.query(combined_text, top_k=1)
            if similar and similar[0]["score"] >= self._semantic_threshold:
                existing_id = similar[0]["id"]
                for c in clusters:
                    if c.cluster_id == existing_id:
                        c.count += 1
                        c.last_seen = time.time()
                        break
                continue

            # New unique cluster
            cluster_id = f"{service}-{raw_hash}"
            now = time.time()
            cluster = ErrorCluster(
                cluster_id=cluster_id,
                service=service,
                level=level,
                error_class=error_class,
                message=message,
                stack_trace=stack_trace,
                count=1,
                first_seen=now,
                last_seen=now,
                raw_hash=raw_hash,
            )
            clusters.append(cluster)

            # Store in vector index for future semantic dedup
            await self._vs.upsert(
                doc_id=cluster_id,
                text=combined_text,
                metadata={
                    "service": service,
                    "error_class": error_class,
                    "level": level,
                    "title": message[:100],
                },
            )

        if clusters:
            logger.info(
                "Log analyst: %d raw events → %d unique clusters", len(events), len(clusters)
            )
        return clusters

    @staticmethod
    def _extract_error_class(text: str) -> str:
        """Extract the leading exception class from a stack trace or message."""
        if not text:
            return "UnknownError"
        import re
        m = re.search(r"([\w\.]+Exception|[\w\.]+Error)(?:\:|\s)", text)
        return m.group(1).split(".")[-1] if m else "UnknownError"
