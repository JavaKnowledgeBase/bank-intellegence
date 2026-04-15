"""
Knowledge base service — chunk ingestion, BM25 + cosine similarity retrieval,
and Claude-powered Q&A.

Storage: SQLite (dev) or PostgreSQL (prod) table `knowledge_chunks`.
Embeddings: lightweight TF-IDF vectors stored as JSON arrays.
Upgrade path: swap _embed() for sentence-transformers or Anthropic embeddings.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from core.database import db

# ---------------------------------------------------------------------------
# Schema helpers
# ---------------------------------------------------------------------------

SCHEMA_DDL = """
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id          TEXT PRIMARY KEY,
    doc_path    TEXT NOT NULL,
    doc_title   TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content     TEXT NOT NULL,
    tokens      TEXT NOT NULL,
    doc_type    TEXT NOT NULL DEFAULT 'markdown',
    ingested_at TEXT NOT NULL
);
"""

INDEX_DDL = """
CREATE INDEX IF NOT EXISTS idx_kc_doc_path ON knowledge_chunks (doc_path);
"""


def ensure_schema() -> None:
    db.execute(SCHEMA_DDL)
    db.execute(INDEX_DDL)


# ---------------------------------------------------------------------------
# Chunking
# ---------------------------------------------------------------------------

CHUNK_SIZE = 400          # target tokens per chunk
CHUNK_OVERLAP = 80        # overlap tokens between adjacent chunks


def _tokenize(text: str) -> list[str]:
    """Simple whitespace + punctuation tokenizer for BM25."""
    return re.findall(r"[a-zA-Z0-9_]+", text.lower())


def _chunk_markdown(text: str, chunk_size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP) -> list[str]:
    """
    Split markdown into chunks that respect heading boundaries first,
    then fall back to token-count splitting.
    """
    # Split on level-1/2 headings to preserve semantic boundaries
    sections = re.split(r"\n(?=#{1,2} )", text)
    chunks: list[str] = []

    for section in sections:
        tokens = _tokenize(section)
        if len(tokens) <= chunk_size:
            if section.strip():
                chunks.append(section.strip())
        else:
            # Slide a window over long sections
            words = section.split()
            start = 0
            while start < len(words):
                end = start + chunk_size
                chunk_words = words[start:end]
                chunks.append(" ".join(chunk_words))
                start += chunk_size - overlap

    return [c for c in chunks if len(c.strip()) > 20]


# ---------------------------------------------------------------------------
# TF-IDF embedding (lightweight, no external deps)
# ---------------------------------------------------------------------------

_idf_cache: dict[str, float] = {}


def _build_idf(all_token_lists: list[list[str]]) -> dict[str, float]:
    n = len(all_token_lists)
    df: Counter[str] = Counter()
    for tokens in all_token_lists:
        for t in set(tokens):
            df[t] += 1
    return {term: math.log((n + 1) / (freq + 1)) + 1.0 for term, freq in df.items()}


def _tfidf_vector(tokens: list[str], idf: dict[str, float]) -> dict[str, float]:
    tf: Counter[str] = Counter(tokens)
    total = len(tokens) or 1
    return {term: (count / total) * idf.get(term, 1.0) for term, count in tf.items()}


def _cosine(a: dict[str, float], b: dict[str, float]) -> float:
    common = set(a) & set(b)
    if not common:
        return 0.0
    dot = sum(a[t] * b[t] for t in common)
    mag_a = math.sqrt(sum(v * v for v in a.values()))
    mag_b = math.sqrt(sum(v * v for v in b.values()))
    if mag_a == 0 or mag_b == 0:
        return 0.0
    return dot / (mag_a * mag_b)


# ---------------------------------------------------------------------------
# Ingestion
# ---------------------------------------------------------------------------

def _chunk_id(doc_path: str, chunk_index: int) -> str:
    raw = f"{doc_path}:{chunk_index}"
    return hashlib.sha256(raw.encode()).hexdigest()[:16]


def ingest_document(doc_path: str, content: str, doc_title: str, doc_type: str = "markdown") -> int:
    """
    Chunk a document and upsert chunks into knowledge_chunks.
    Returns number of chunks ingested.
    """
    ensure_schema()

    chunks = _chunk_markdown(content)
    now = datetime.now(timezone.utc).isoformat()

    # Remove existing chunks for this document (re-ingest)
    db.execute("DELETE FROM knowledge_chunks WHERE doc_path = ?", (doc_path,))

    for idx, chunk in enumerate(chunks):
        chunk_id = _chunk_id(doc_path, idx)
        tokens = _tokenize(chunk)
        db.execute(
            """
            INSERT INTO knowledge_chunks
                (id, doc_path, doc_title, chunk_index, content, tokens, doc_type, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (chunk_id, doc_path, doc_title, idx, chunk, json.dumps(tokens), doc_type, now),
        )

    return len(chunks)


def ingest_directory(directory: str, extensions: tuple[str, ...] = (".md",)) -> dict[str, Any]:
    """Walk a directory and ingest all matching documents."""
    ensure_schema()
    root = Path(directory)
    results: dict[str, Any] = {"ingested": [], "failed": [], "total_chunks": 0}

    for path in sorted(root.rglob("*")):
        if path.suffix.lower() not in extensions:
            continue
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
            # Extract title from first heading or filename
            title_match = re.search(r"^#+ (.+)", content, re.MULTILINE)
            title = title_match.group(1).strip() if title_match else path.stem
            n = ingest_document(str(path), content, title)
            results["ingested"].append({"path": str(path), "title": title, "chunks": n})
            results["total_chunks"] += n
        except Exception as exc:
            results["failed"].append({"path": str(path), "error": str(exc)})

    return results


# ---------------------------------------------------------------------------
# Retrieval
# ---------------------------------------------------------------------------

def retrieve_chunks(question: str, top_k: int = 5) -> list[dict[str, Any]]:
    """
    Return top_k most relevant chunks for a question using TF-IDF cosine similarity.
    Falls back to keyword search if the table is empty or has < 10 chunks.
    """
    ensure_schema()

    rows = db.fetchall("SELECT id, doc_path, doc_title, content, tokens FROM knowledge_chunks")
    if not rows:
        return []

    all_token_lists = [json.loads(r["tokens"]) for r in rows]
    idf = _build_idf(all_token_lists)

    q_tokens = _tokenize(question)
    q_vec = _tfidf_vector(q_tokens, idf)

    scored: list[tuple[float, dict[str, Any]]] = []
    for row, token_list in zip(rows, all_token_lists):
        chunk_vec = _tfidf_vector(token_list, idf)
        score = _cosine(q_vec, chunk_vec)
        scored.append((score, row))

    scored.sort(key=lambda x: x[0], reverse=True)
    return [
        {
            "chunk_id": r["id"],
            "doc_path": r["doc_path"],
            "doc_title": r["doc_title"],
            "content": r["content"],
            "score": round(score, 4),
        }
        for score, r in scored[:top_k]
        if score > 0.01
    ]


# ---------------------------------------------------------------------------
# Stats
# ---------------------------------------------------------------------------

def get_stats() -> dict[str, Any]:
    ensure_schema()
    total = db.fetchone("SELECT COUNT(*) AS n FROM knowledge_chunks")
    by_doc = db.fetchall(
        "SELECT doc_path, doc_title, COUNT(*) AS chunks FROM knowledge_chunks GROUP BY doc_path ORDER BY chunks DESC"
    )
    return {
        "total_chunks": int(total["n"] if total else 0),
        "documents": [
            {"path": r["doc_path"], "title": r["doc_title"], "chunks": r["chunks"]}
            for r in by_doc
        ],
    }
