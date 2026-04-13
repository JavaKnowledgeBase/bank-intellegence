"""
Vector store abstraction — ChromaDB (local dev) or Pinecone (prod).
Used for semantic deduplication of error clusters and similar-issue lookup.
"""
from __future__ import annotations

import hashlib
import logging
from typing import Optional

logger = logging.getLogger(__name__)


class VectorStore:
    """Abstract interface — implemented by ChromaDB or Pinecone backends."""

    async def upsert(self, doc_id: str, text: str, metadata: dict) -> None:
        raise NotImplementedError

    async def query(self, text: str, top_k: int = 5, filter: dict | None = None) -> list[dict]:
        raise NotImplementedError

    async def delete(self, doc_id: str) -> None:
        raise NotImplementedError


class ChromaVectorStore(VectorStore):
    """Local ChromaDB backend — for development and CI."""

    def __init__(self, path: str, collection_name: str = "csip-logs"):
        self._path = path
        self._collection_name = collection_name
        self._client = None
        self._collection = None

    def _ensure_init(self):
        if self._client is None:
            import chromadb
            from chromadb.config import Settings
            self._client = chromadb.PersistentClient(
                path=self._path,
                settings=Settings(anonymized_telemetry=False),
            )
            self._collection = self._client.get_or_create_collection(
                name=self._collection_name,
                metadata={"hnsw:space": "cosine"},
            )

    async def upsert(self, doc_id: str, text: str, metadata: dict) -> None:
        self._ensure_init()
        self._collection.upsert(
            ids=[doc_id],
            documents=[text],
            metadatas=[{k: str(v) for k, v in metadata.items()}],
        )

    async def query(self, text: str, top_k: int = 5, filter: dict | None = None) -> list[dict]:
        self._ensure_init()
        results = self._collection.query(
            query_texts=[text],
            n_results=min(top_k, self._collection.count() or 1),
            where=filter,
            include=["documents", "metadatas", "distances"],
        )
        hits = []
        for i, doc_id in enumerate(results["ids"][0]):
            hits.append({
                "id": doc_id,
                "document": results["documents"][0][i],
                "metadata": results["metadatas"][0][i],
                "score": 1 - results["distances"][0][i],  # cosine distance → similarity
            })
        return hits

    async def delete(self, doc_id: str) -> None:
        self._ensure_init()
        self._collection.delete(ids=[doc_id])


class PineconeVectorStore(VectorStore):
    """Pinecone backend — for production."""

    def __init__(self, api_key: str, environment: str, index_name: str):
        from pinecone import Pinecone
        pc = Pinecone(api_key=api_key, environment=environment)
        self._index = pc.Index(index_name)
        logger.info("Pinecone vector store connected: %s", index_name)

    async def upsert(self, doc_id: str, text: str, metadata: dict) -> None:
        # Pinecone requires pre-computed embeddings; use simple hash-based mock
        # In production, replace with an embedding model call (e.g., text-embedding-3-small)
        vector = _text_to_vector_mock(text)
        self._index.upsert(vectors=[(doc_id, vector, metadata)])

    async def query(self, text: str, top_k: int = 5, filter: dict | None = None) -> list[dict]:
        vector = _text_to_vector_mock(text)
        result = self._index.query(vector=vector, top_k=top_k, filter=filter, include_metadata=True)
        return [
            {"id": m["id"], "score": m["score"], "metadata": m.get("metadata", {})}
            for m in result["matches"]
        ]

    async def delete(self, doc_id: str) -> None:
        self._index.delete(ids=[doc_id])


def _text_to_vector_mock(text: str, dim: int = 1536) -> list[float]:
    """
    Deterministic mock embedding — do NOT use in production.
    Replace with openai.embeddings or a local model.
    """
    h = int(hashlib.md5(text.encode()).hexdigest(), 16)
    import random
    rng = random.Random(h)
    raw = [rng.gauss(0, 1) for _ in range(dim)]
    norm = sum(x**2 for x in raw) ** 0.5
    return [x / norm for x in raw]


def create_vector_store(backend: str, **kwargs) -> VectorStore:
    if backend == "pinecone":
        return PineconeVectorStore(
            api_key=kwargs["api_key"],
            environment=kwargs["environment"],
            index_name=kwargs.get("index_name", "csip-logs"),
        )
    return ChromaVectorStore(
        path=kwargs.get("path", "./data/chromadb"),
        collection_name=kwargs.get("index_name", "csip-logs"),
    )
