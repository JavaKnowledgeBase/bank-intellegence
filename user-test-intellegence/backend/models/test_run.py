from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


RunStatus = Literal["queued", "running", "passed", "failed", "partial"]


class SuiteRunCreate(BaseModel):
    trigger: Literal["manual", "deploy", "regression"] = "manual"
    trigger_actor: str = "local-user"
    commit_sha: str | None = None
    branch: str | None = None


class ShardResultIn(BaseModel):
    suite_run_id: str
    shard_number: int
    passed: int
    failed: int
    errored: int = 0
    duration_ms: int
    test_case_ids: list[str] = Field(default_factory=list)
    failure_messages: list[str] = Field(default_factory=list)


class ShardResult(BaseModel):
    suite_run_id: str
    shard_number: int
    status: Literal["queued", "running", "passed", "failed"]
    passed: int
    failed: int
    errored: int
    duration_ms: int
    test_case_ids: list[str]
    failure_messages: list[str] = Field(default_factory=list)


class SuiteRun(BaseModel):
    id: str
    service: str
    status: RunStatus
    trigger: str
    trigger_actor: str
    commit_sha: str | None = None
    branch: str | None = None
    total_tests: int
    total_shards: int
    completed_shards: int
    passed: int
    failed: int
    errored: int
    duration_ms: int = 0
    started_at: datetime
    completed_at: datetime | None = None
    shards: list[ShardResult] = Field(default_factory=list)
