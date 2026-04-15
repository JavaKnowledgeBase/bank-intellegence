#!/usr/bin/env python3
"""
Standalone script to ingest all CIBAP documentation into the CTIP knowledge base.

Usage (from the backend/ directory):
    python scripts/ingest_docs.py

Or with a custom docs root:
    python scripts/ingest_docs.py --docs-root /path/to/docs

The script ingests:
  - docs/services/           — service architecture documents
  - docs/                    — project architecture, developer guide, testing guide
  - cibap/docs/              — runbooks, service help, architecture notes
  - USER_MANUAL.md           — complete user manual (generated)

Documents are chunked into ~400-token segments with 80-token overlap and
stored in the `knowledge_chunks` SQLite table (or PostgreSQL in prod).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Make sure we can import from the backend package
backend_root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(backend_root))

# Set database to the default SQLite path before importing services
import os
os.environ.setdefault("DATABASE_URL", f"sqlite+pysqlite:///{backend_root}/data/ctip.db")

from services.knowledge_service import ingest_directory, ingest_document, get_stats

# ---------------------------------------------------------------------------
# Well-known document directories relative to the monorepo root
# ---------------------------------------------------------------------------

def find_monorepo_root() -> Path:
    """Walk up from backend/ to find the monorepo root (contains cibap/)."""
    candidate = backend_root
    for _ in range(6):
        if (candidate / "cibap").exists() and (candidate / "docs").exists():
            return candidate
        candidate = candidate.parent
    # Fallback: assume user-test-intellegence is inside cibap/
    return backend_root.parents[2]  # user-test-intellegence/backend -> cibap -> monorepo


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest CIBAP docs into CTIP knowledge base")
    parser.add_argument(
        "--docs-root",
        default=None,
        help="Monorepo root directory. Auto-detected if omitted.",
    )
    parser.add_argument(
        "--extensions",
        default=".md",
        help="Comma-separated file extensions to ingest (default: .md)",
    )
    args = parser.parse_args()

    monorepo = Path(args.docs_root) if args.docs_root else find_monorepo_root()
    extensions = tuple(e.strip() for e in args.extensions.split(","))

    print(f"Monorepo root: {monorepo}")
    print(f"Extensions:    {extensions}")
    print()

    # Directories to ingest (in order of priority)
    targets = [
        monorepo / "docs" / "services",
        monorepo / "docs",
        monorepo / "cibap" / "docs",
        monorepo / "cibap" / "docs" / "architecture",
        monorepo / "cibap" / "docs" / "runbooks",
        monorepo / "cibap" / "docs" / "service-help",
        monorepo / "cibap" / "support-intellegence",       # CSIP docs
        monorepo / "cibap" / "user-test-intellegence",     # CTIP docs
    ]

    total_chunks = 0
    total_docs = 0
    total_failed = 0

    for target in targets:
        if not target.exists():
            print(f"  [SKIP] {target} — not found")
            continue
        print(f"  [INGEST] {target}")
        result = ingest_directory(str(target), extensions)
        for doc in result["ingested"]:
            print(f"    + {Path(doc['path']).name} → {doc['chunks']} chunks")
        for doc in result["failed"]:
            print(f"    ! FAILED {doc['path']}: {doc['error']}")
        total_docs += len(result["ingested"])
        total_chunks += result["total_chunks"]
        total_failed += len(result["failed"])
        print()

    # Also ingest root-level README files
    for readme in monorepo.glob("*/README.md"):
        try:
            content = readme.read_text(encoding="utf-8", errors="replace")
            n = ingest_document(str(readme), content, readme.parent.name + " README")
            print(f"  [INGEST] {readme.name} ({readme.parent.name}) → {n} chunks")
            total_docs += 1
            total_chunks += n
        except Exception as e:
            print(f"  [FAIL]  {readme}: {e}")
            total_failed += 1

    print()
    print("=" * 60)
    print(f"Ingestion complete")
    print(f"  Documents : {total_docs}")
    print(f"  Chunks    : {total_chunks}")
    print(f"  Failed    : {total_failed}")
    print()

    stats = get_stats()
    print(f"Knowledge base now contains {stats['total_chunks']} chunks across {len(stats['documents'])} documents.")
    print()
    print("Test a query:")
    print('  curl -X POST http://localhost:8091/api/v1/knowledge/ask \\')
    print('    -H "Authorization: Bearer <token>" \\')
    print('    -H "Content-Type: application/json" \\')
    print('    -d \'{"question": "What fraud risk score triggers an auto-block?"}\'')


if __name__ == "__main__":
    main()
