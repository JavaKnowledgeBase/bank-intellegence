from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone

from core.evidence_generator import TestEvidenceGenerator
from core.repositories import ExecutionEventRepository, ImprovementRepository, ScoutRepository, SuiteRunRepository, TestCaseRepository
from services.search_backend import SearchBackendClient


class SearchIndexService:
    def __init__(self) -> None:
        self.test_cases = TestCaseRepository()
        self.runs = SuiteRunRepository()
        self.events = ExecutionEventRepository()
        self.scout = ScoutRepository()
        self.improvements = ImprovementRepository()
        self.evidence = TestEvidenceGenerator()
        self.backend = SearchBackendClient()
        self._last_signature: str | None = None
        self._last_sync_at: str | None = None

    def _serialize_metadata(self, metadata: dict) -> str:
        return " ".join(str(value) for value in metadata.values() if value not in (None, "", [], {})).lower()

    def _document_signature(self, documents: list[dict]) -> str:
        payload = json.dumps(documents, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def _sync_summary(self, *, indexed: bool, documents: int, sync_state: str) -> dict:
        return {
            "indexed": indexed,
            "documents": documents,
            "documents_indexed": documents if indexed else 0,
            "last_sync_at": self._last_sync_at,
            "sync_state": sync_state,
        }

    def _sync_backend(self, documents: list[dict], *, force: bool = False) -> dict:
        status = self.backend.status().model_dump()
        if status["active_backend"] != "elasticsearch":
            return {**status, **self._sync_summary(indexed=False, documents=len(documents), sync_state="local_only")}

        signature = self._document_signature(documents)
        if not force and signature == self._last_signature:
            return {**status, **self._sync_summary(indexed=True, documents=len(documents), sync_state="unchanged")}

        indexed = self.backend.replace_documents(documents)
        status = self.backend.status().model_dump()
        if indexed:
            self._last_signature = signature
            self._last_sync_at = datetime.now(timezone.utc).isoformat()
        return {**status, **self._sync_summary(indexed=indexed, documents=len(documents), sync_state="synced" if indexed else "local_only")}

    def build_index(self) -> list[dict]:
        documents: list[dict] = []
        for test_case in self.test_cases.list():
            metadata = {
                "endpoint": test_case.endpoint,
                "priority": test_case.priority,
                "status": test_case.status,
                "source": test_case.source,
                "last_run_status": test_case.last_run_status,
            }
            documents.append(
                {
                    "kind": "test_case",
                    "id": test_case.id,
                    "service": test_case.target_service,
                    "status": test_case.status,
                    "level": None,
                    "event_type": None,
                    "recorded_at": test_case.updated_at.isoformat(),
                    "title": test_case.title,
                    "content": " ".join(
                        [
                            test_case.title,
                            test_case.scenario_description,
                            test_case.endpoint,
                            test_case.last_run_status,
                            *test_case.tags,
                        ]
                    ).lower(),
                    "tags": test_case.tags,
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        for run in self.runs.list():
            failure_messages = [message for shard in run.shards for message in shard.failure_messages]
            metadata = {
                "status": run.status,
                "failed": run.failed,
                "passed": run.passed,
                "duration_ms": run.duration_ms,
                "trigger": run.trigger,
                "trigger_actor": run.trigger_actor,
                "branch": run.branch,
                "commit_sha": run.commit_sha,
            }
            documents.append(
                {
                    "kind": "suite_run",
                    "id": run.id,
                    "service": run.service,
                    "status": run.status,
                    "level": None,
                    "event_type": None,
                    "recorded_at": (run.completed_at or run.started_at).isoformat() if (run.completed_at or run.started_at) else None,
                    "title": f"{run.service} {run.status} suite",
                    "content": " ".join([run.service, run.status, run.trigger, run.trigger_actor, *(failure_messages or [])]).lower(),
                    "tags": [],
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        for event in self.events.list_recent(limit=200):
            metadata = {
                "level": event["level"],
                "suite_run_id": event["suite_run_id"],
            }
            documents.append(
                {
                    "kind": "execution_event",
                    "id": str(event["id"]),
                    "service": event["service"],
                    "status": None,
                    "level": event["level"],
                    "event_type": event["event_type"],
                    "recorded_at": event["created_at"],
                    "title": event["event_type"],
                    "content": f"{event['event_type']} {event['message']} {event['service']} {event['suite_run_id']}".lower(),
                    "tags": [],
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        for item in self.evidence.list_evidence():
            metadata = {
                "path": item["path"],
                "evidence_hash": item["evidence_hash"],
            }
            documents.append(
                {
                    "kind": "evidence",
                    "id": item["suite_run_id"] or item["path"],
                    "service": item.get("service") or "unknown",
                    "status": None,
                    "level": None,
                    "event_type": None,
                    "recorded_at": item.get("recorded_at"),
                    "title": "evidence package",
                    "content": f"{item.get('service', '')} evidence {item.get('suite_run_id', '')} {item.get('evidence_hash', '')}".lower(),
                    "tags": [],
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        for observation in self.scout.list():
            metadata = {
                "domain": observation.domain,
                "safety_status": observation.safety_status,
                "source_url": observation.source_url,
            }
            documents.append(
                {
                    "kind": "scout_observation",
                    "id": observation.id,
                    "service": observation.proposed_service,
                    "status": observation.safety_status,
                    "level": None,
                    "event_type": None,
                    "recorded_at": observation.discovered_at.isoformat(),
                    "title": observation.title,
                    "content": " ".join([observation.title, observation.summary, observation.domain, *observation.tags]).lower(),
                    "tags": observation.tags,
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        for proposal in self.improvements.list():
            metadata = {
                "area": proposal.area,
                "status": proposal.status,
            }
            documents.append(
                {
                    "kind": "improvement_proposal",
                    "id": proposal.id,
                    "service": "platform",
                    "status": proposal.status,
                    "level": None,
                    "event_type": None,
                    "recorded_at": proposal.created_at.isoformat(),
                    "title": proposal.title,
                    "content": " ".join([proposal.title, proposal.summary, proposal.expected_impact, proposal.area, proposal.status]).lower(),
                    "tags": [],
                    "metadata_text": self._serialize_metadata(metadata),
                    "metadata": metadata,
                }
            )
        return documents

    def sync_index(self, *, force: bool = True) -> dict:
        docs = self.build_index()
        return self._sync_backend(docs, force=force)

    def search(
        self,
        query: str,
        service: str | None = None,
        kind: str | None = None,
        status: str | None = None,
        level: str | None = None,
        sort: str = "relevance",
        limit: int = 30,
    ) -> dict:
        docs = self.build_index()
        sync_summary = self._sync_backend(docs, force=False)
        backend_result = self.backend.search(query, service, kind, status, level, sort, limit)
        if backend_result:
            return {**backend_result, **sync_summary}

        needle = query.strip().lower()
        results = []
        for doc in docs:
            if service and doc["service"] != service:
                continue
            if kind and doc["kind"] != kind:
                continue
            if status and doc.get("status") != status:
                continue
            if level and doc.get("level") != level:
                continue
            score = 0
            if needle in doc["title"].lower():
                score += 3
            if needle and needle in doc["content"]:
                score += 1
            if needle and needle in doc.get("metadata_text", ""):
                score += 1
            if not needle:
                score = 1
            if score > 0:
                results.append({**doc, "score": score})
        if sort == "newest":
            results.sort(key=lambda item: (item.get("recorded_at") or "", item["score"]), reverse=True)
        elif sort == "oldest":
            results.sort(key=lambda item: (item.get("recorded_at") or "", -item["score"]))
        else:
            results.sort(key=lambda item: (item["score"], item.get("recorded_at") or ""), reverse=True)
        return {"query": query, "count": len(results), "results": results[:limit], **sync_summary}

    def analytics(self) -> dict:
        docs = self.build_index()
        sync_summary = self._sync_backend(docs, force=False)
        backend_result = self.backend.analytics()
        if backend_result:
            return {**backend_result, **sync_summary}
        by_kind: dict[str, int] = {}
        by_service: dict[str, int] = {}
        for doc in docs:
            by_kind[doc["kind"]] = by_kind.get(doc["kind"], 0) + 1
            by_service[doc["service"]] = by_service.get(doc["service"], 0) + 1
        return {"total_documents": len(docs), "by_kind": by_kind, "by_service": by_service, **sync_summary}
