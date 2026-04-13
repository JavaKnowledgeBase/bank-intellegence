"""
Immutable audit writer — append-only JSONL with SHA-256 integrity chain.
Optionally uploads to S3 for compliance archival.
"""
from __future__ import annotations

import hashlib
import json
import logging
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import func, select

from models.audit import AuditRecordORM, compute_record_hash

logger = logging.getLogger(__name__)


class ImmutableAuditWriter:
    """
    Writes audit records to:
      1. PostgreSQL (queryable, paginated API)
      2. Local JSONL files (per day)
      3. AWS S3 (async, optional — for compliance retention)
    Each record includes a SHA-256 hash of itself + previous record hash.
    """

    def __init__(
        self,
        local_path: Path,
        db_session_factory,
        s3_client=None,
        s3_bucket: str = "csip-audit-logs",
        s3_enabled: bool = False,
    ):
        self._local_path = local_path
        self._session_factory = db_session_factory
        self._s3 = s3_client
        self._s3_bucket = s3_bucket
        self._s3_enabled = s3_enabled
        self._last_hash = "GENESIS"
        local_path.mkdir(parents=True, exist_ok=True)

    async def write(
        self,
        event_type: str,
        actor: str,
        summary: str,
        details: dict,
        app_id: Optional[str] = None,
        issue_id: Optional[str] = None,
        team_id: Optional[str] = None,
        actor_ip: Optional[str] = None,
        diff: Optional[str] = None,
    ) -> str:
        """Write an audit record. Returns the record's integrity hash."""
        now = datetime.now(timezone.utc)

        diff_hash = hashlib.sha256(diff.encode()).hexdigest() if diff else None

        record_id = str(uuid.uuid4())
        seq = await self._next_sequence()

        record_data = {
            "id": record_id,
            "sequence_number": seq,
            "event_type": event_type,
            "app_id": app_id,
            "issue_id": issue_id,
            "team_id": team_id,
            "actor": actor,
            "summary": summary,
            "details": details,
            "diff_hash": diff_hash,
            "previous_hash": self._last_hash,
            "timestamp": now.isoformat(),
        }
        record_hash = compute_record_hash(record_data)
        self._last_hash = record_hash

        orm = AuditRecordORM(
            id=record_id,
            sequence_number=seq,
            event_type=event_type,
            app_id=app_id,
            issue_id=issue_id,
            team_id=team_id,
            actor=actor,
            actor_ip=actor_ip,
            summary=summary,
            details=details,
            diff=diff,
            diff_hash=diff_hash,
            previous_hash=record_data["previous_hash"],
            record_hash=record_hash,
            timestamp=now,
        )

        async with self._session_factory() as session:
            session.add(orm)
            await session.commit()

        # Append to local JSONL
        await self._write_local(record_data | {"record_hash": record_hash})

        if self._s3_enabled and self._s3:
            await self._upload_s3(record_data | {"record_hash": record_hash}, now.date().isoformat())

        return record_hash

    async def verify_integrity(self, date_str: str) -> bool:
        """Verify no records have been tampered with for a given date."""
        log_file = self._local_path / f"{date_str}.jsonl"
        if not log_file.exists():
            return True
        prev = "GENESIS"
        with open(log_file) as f:
            for line in f:
                record = json.loads(line)
                claimed = record.pop("record_hash")
                expected = compute_record_hash(record)
                if claimed != expected:
                    return False
                prev = claimed
        return True

    async def _next_sequence(self) -> int:
        try:
            async with self._session_factory() as session:
                result = await session.execute(
                    select(func.coalesce(func.max(AuditRecordORM.sequence_number), 0))
                )
                return (result.scalar() or 0) + 1
        except Exception:
            return 1

    async def _write_local(self, record: dict) -> None:
        date_str = record["timestamp"][:10]
        log_file = self._local_path / f"{date_str}.jsonl"
        with open(log_file, "a") as f:
            f.write(json.dumps(record, default=str) + "\n")

    async def _upload_s3(self, record: dict, date_str: str) -> None:
        try:
            import boto3, io
            key = f"audit/{date_str}/{record['id']}.json"
            body = json.dumps(record, default=str).encode()
            self._s3.put_object(Bucket=self._s3_bucket, Key=key, Body=body)
        except Exception as exc:
            logger.warning("S3 audit upload failed: %s", exc)
