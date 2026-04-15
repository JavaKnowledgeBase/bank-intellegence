"""
/api/v1/knowledge — document ingestion, retrieval, and Claude-powered Q&A.
"""

from __future__ import annotations

import os
from typing import Annotated, Any

from fastapi import APIRouter, Body, Depends, HTTPException, Query
from pydantic import BaseModel

from api.v1.auth import require_role
from services import knowledge_service

router = APIRouter(prefix="/api/v1/knowledge", tags=["knowledge"])


# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------

class IngestDirectoryRequest(BaseModel):
    directory: str
    extensions: list[str] = [".md"]


class IngestDocumentRequest(BaseModel):
    doc_path: str
    doc_title: str
    content: str
    doc_type: str = "markdown"


class AskRequest(BaseModel):
    question: str
    context_limit: int = 5


class AskResponse(BaseModel):
    question: str
    answer: str
    sources: list[dict[str, Any]]
    model: str


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.post("/ingest/directory")
def ingest_directory(
    body: IngestDirectoryRequest,
    _: str = Depends(require_role(["admin"])),
) -> dict[str, Any]:
    """
    Walk a directory and ingest all markdown (or specified) files.
    Admin only.
    """
    result = knowledge_service.ingest_directory(body.directory, tuple(body.extensions))
    return result


@router.post("/ingest/document")
def ingest_document(
    body: IngestDocumentRequest,
    _: str = Depends(require_role(["admin", "qa"])),
) -> dict[str, Any]:
    """Ingest a single document by providing its content directly."""
    n = knowledge_service.ingest_document(
        body.doc_path, body.content, body.doc_title, body.doc_type
    )
    return {"doc_path": body.doc_path, "chunks_ingested": n}


@router.get("/search")
def search_chunks(
    query: str = Query(..., min_length=2),
    top_k: int = Query(5, ge=1, le=20),
    token: str = Depends(require_role(["admin", "qa", "viewer"])),
) -> dict[str, Any]:
    """Semantic search over the knowledge base."""
    chunks = knowledge_service.retrieve_chunks(query, top_k=top_k)
    return {"query": query, "count": len(chunks), "results": chunks}


@router.post("/ask")
def ask(
    body: AskRequest,
    _: str = Depends(require_role(["admin", "qa", "viewer"])),
) -> AskResponse:
    """
    Answer a question using retrieved context chunks + Claude.
    Requires ANTHROPIC_API_KEY environment variable.
    """
    api_key = os.getenv("ANTHROPIC_API_KEY", "")
    if not api_key:
        raise HTTPException(status_code=503, detail="ANTHROPIC_API_KEY not configured")

    chunks = knowledge_service.retrieve_chunks(body.question, top_k=body.context_limit)
    if not chunks:
        return AskResponse(
            question=body.question,
            answer="No relevant documentation found. Please ingest documents first via POST /api/v1/knowledge/ingest/directory.",
            sources=[],
            model="none",
        )

    context_text = "\n\n---\n\n".join(
        f"[Source: {c['doc_title']}]\n{c['content']}" for c in chunks
    )

    try:
        import anthropic

        client = anthropic.Anthropic(api_key=api_key)
        message = client.messages.create(
            model="claude-sonnet-4-6",
            max_tokens=1024,
            system=(
                "You are a helpful assistant for the CIBAP banking platform. "
                "Answer questions using ONLY the provided documentation context. "
                "Be concise, accurate, and cite the source document when relevant. "
                "If the answer is not in the context, say so clearly."
            ),
            messages=[
                {
                    "role": "user",
                    "content": (
                        f"Documentation context:\n\n{context_text}\n\n"
                        f"Question: {body.question}"
                    ),
                }
            ],
        )
        answer = message.content[0].text
        model_used = message.model
    except ImportError:
        # Fallback: return top chunk if anthropic SDK not installed
        answer = (
            f"(anthropic SDK not installed — showing top context)\n\n"
            f"{chunks[0]['content']}"
        )
        model_used = "fallback"
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Claude API error: {exc}") from exc

    return AskResponse(
        question=body.question,
        answer=answer,
        sources=[
            {"doc_title": c["doc_title"], "doc_path": c["doc_path"], "score": c["score"]}
            for c in chunks
        ],
        model=model_used,
    )


@router.get("/stats")
def get_stats(
    _: str = Depends(require_role(["admin", "qa", "viewer"])),
) -> dict[str, Any]:
    """Return knowledge base statistics."""
    return knowledge_service.get_stats()


@router.delete("/clear")
def clear_knowledge(
    _: str = Depends(require_role(["admin"])),
) -> dict[str, Any]:
    """Remove all chunks from the knowledge base. Admin only."""
    from core.database import db

    knowledge_service.ensure_schema()
    db.execute("DELETE FROM knowledge_chunks")
    return {"status": "cleared"}
