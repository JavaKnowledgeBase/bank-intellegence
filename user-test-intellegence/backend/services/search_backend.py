from __future__ import annotations

from elasticsearch import Elasticsearch

from core.config import settings


class SearchBackendStatus:
    def __init__(self, mode: str, active_backend: str, available: bool):
        self.mode = mode
        self.active_backend = active_backend
        self.available = available

    def model_dump(self) -> dict:
        return {
            "mode": self.mode,
            "active_backend": self.active_backend,
            "available": self.available,
        }


class SearchBackendClient:
    INDEX_MAPPINGS = {
        "properties": {
            "id": {"type": "keyword"},
            "kind": {"type": "keyword"},
            "service": {"type": "keyword"},
            "status": {"type": "keyword"},
            "level": {"type": "keyword"},
            "event_type": {"type": "keyword"},
            "recorded_at": {"type": "date"},
            "title": {"type": "text"},
            "content": {"type": "text"},
            "tags": {"type": "keyword"},
            "metadata_text": {"type": "text"},
            "metadata": {"type": "object", "enabled": True},
        }
    }

    def __init__(self) -> None:
        self.mode = settings.search_backend_mode
        self.index_name = settings.elasticsearch_index_name
        self.client = Elasticsearch(settings.resolved_elasticsearch_url, verify_certs=False, request_timeout=1)

    def _can_use_elasticsearch(self) -> bool:
        if self.mode == "local":
            return False
        try:
            return bool(self.client.ping())
        except Exception:
            return False

    def status(self) -> SearchBackendStatus:
        available = self._can_use_elasticsearch()
        active = "elasticsearch" if available else "local"
        return SearchBackendStatus(mode=self.mode, active_backend=active, available=available)

    def ensure_index(self) -> bool:
        if not self._can_use_elasticsearch():
            return False
        if not self.client.indices.exists(index=self.index_name):
            self.client.indices.create(index=self.index_name, mappings=self.INDEX_MAPPINGS)
        else:
            self.client.indices.put_mapping(index=self.index_name, properties=self.INDEX_MAPPINGS["properties"])
        return True

    def replace_documents(self, documents: list[dict]) -> bool:
        if not self.ensure_index():
            return False
        self.client.options(ignore_status=[404]).delete_by_query(index=self.index_name, query={"match_all": {}}, refresh=True)
        operations: list[dict] = []
        for document in documents:
            operations.append({"index": {"_index": self.index_name, "_id": f"{document['kind']}-{document['id']}"}})
            operations.append(document)
        if operations:
            self.client.bulk(operations=operations, refresh=True)
        return True

    def count_documents(self) -> int:
        if not self.ensure_index():
            return 0
        response = self.client.count(index=self.index_name, query={"match_all": {}})
        return int(response["count"])

    def search(
        self,
        query: str,
        service: str | None = None,
        kind: str | None = None,
        status: str | None = None,
        level: str | None = None,
        sort: str = "relevance",
        limit: int = 30,
    ) -> dict | None:
        if not self.ensure_index():
            return None
        filters = []
        if service:
            filters.append({"term": {"service": service}})
        if kind:
            filters.append({"term": {"kind": kind}})
        if status:
            filters.append({"term": {"status": status}})
        if level:
            filters.append({"term": {"level": level}})
        query_body = {
            "bool": {
                "must": [{"multi_match": {"query": query, "fields": ["title^3", "content", "metadata_text"]}}] if query else [{"match_all": {}}],
                "filter": filters,
            }
        }
        search_kwargs = {"index": self.index_name, "query": query_body, "size": limit}
        if sort == "newest":
            search_kwargs["sort"] = [{"recorded_at": {"order": "desc", "missing": "_last"}}, {"_score": "desc"}]
        elif sort == "oldest":
            search_kwargs["sort"] = [{"recorded_at": {"order": "asc", "missing": "_last"}}, {"_score": "desc"}]
        response = self.client.search(**search_kwargs)
        hits = response["hits"]["hits"]
        return {
            "query": query,
            "count": len(hits),
            "results": [{**hit["_source"], "score": hit.get("_score", 0)} for hit in hits],
        }

    def analytics(self) -> dict | None:
        if not self.ensure_index():
            return None
        response = self.client.search(
            index=self.index_name,
            size=0,
            aggs={
                "by_kind": {"terms": {"field": "kind", "size": 20}},
                "by_service": {"terms": {"field": "service", "size": 20}},
            },
        )
        total = response["hits"]["total"]
        total_documents = total["value"] if isinstance(total, dict) else int(total)
        return {
            "total_documents": int(total_documents),
            "by_kind": {bucket["key"]: bucket["doc_count"] for bucket in response["aggregations"]["by_kind"]["buckets"]},
            "by_service": {bucket["key"]: bucket["doc_count"] for bucket in response["aggregations"]["by_service"]["buckets"]},
        }
