from __future__ import annotations

import hashlib
import json
from pathlib import Path

from core.config import settings


class TestEvidenceGenerator:
    def __init__(self, base_dir: Path | None = None):
        self.base_dir = base_dir or settings.evidence_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def generate(self, suite_run: dict) -> str:
        payload = {
            "evidence_type": "suite_execution",
            "suite_run": suite_run,
        }
        payload_json = json.dumps(payload, sort_keys=True)
        payload["evidence_hash"] = hashlib.sha256(payload_json.encode()).hexdigest()
        filename = f"{suite_run['id']}.json"
        path = self.base_dir / filename
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        return str(path)

    def list_evidence(self) -> list[dict]:
        if not self.base_dir.exists():
            return []
        items = []
        for path in sorted(self.base_dir.glob("*.json"), reverse=True):
            payload = json.loads(path.read_text(encoding="utf-8"))
            suite_run = payload.get("suite_run", {})
            items.append(
                {
                    "path": str(path),
                    "suite_run_id": suite_run.get("id"),
                    "service": suite_run.get("service"),
                    "evidence_hash": payload.get("evidence_hash"),
                    "recorded_at": suite_run.get("completed_at") or suite_run.get("started_at"),
                }
            )
        return items

    def get_evidence(self, suite_run_id: str) -> dict | None:
        path = self.base_dir / f"{suite_run_id}.json"
        if not path.exists():
            return None
        return json.loads(path.read_text(encoding="utf-8"))
