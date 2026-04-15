#!/usr/bin/env python3
"""
Load web-sourced test cases from data/web_test_cases.json into the CTIP database.

Usage (from backend/ directory):
    python scripts/seed_web_test_cases.py

Idempotent: existing test cases with the same id are skipped.
"""

from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

backend_root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(backend_root))

import os
os.environ.setdefault("DATABASE_URL", f"sqlite+pysqlite:///{backend_root}/data/ctip.db")

from core.database import db
from core.migrations import ensure_database_schema


def main() -> None:
    ensure_database_schema()

    data_file = backend_root / "data" / "web_test_cases.json"
    if not data_file.exists():
        print(f"ERROR: {data_file} not found")
        sys.exit(1)

    with data_file.open(encoding="utf-8") as f:
        payload = json.load(f)

    test_cases = payload.get("test_cases", [])
    now = datetime.now(timezone.utc).isoformat()
    inserted = 0
    skipped = 0

    for tc in test_cases:
        existing = db.fetchone("SELECT id FROM test_cases WHERE id = ?", (tc["id"],))
        if existing:
            skipped += 1
            continue

        db.execute(
            """
            INSERT INTO test_cases (
                id, title, scenario_description, target_service, endpoint,
                priority, status, source, tags, steps, last_run_status,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                tc["id"],
                tc["title"],
                tc["scenario_description"],
                tc["target_service"],
                tc["endpoint"],
                tc["priority"],
                "validated",
                tc["source"],
                json.dumps(tc["tags"]),
                json.dumps(tc["steps"]),
                "not_run",
                now,
                now,
            ),
        )
        inserted += 1
        print(f"  + {tc['id']}: {tc['title']}")

    print()
    print(f"Inserted: {inserted}  Skipped (already exist): {skipped}")
    total = db.fetchone("SELECT COUNT(*) AS n FROM test_cases")
    print(f"Total test cases in DB: {total['n'] if total else '?'}")


if __name__ == "__main__":
    main()
