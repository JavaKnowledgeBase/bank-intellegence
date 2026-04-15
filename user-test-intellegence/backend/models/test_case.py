from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


TestCaseStatus = Literal["draft", "active", "validated", "deprecated"]


class TestStep(BaseModel):
    order: int
    action: str
    expected_response: str


class TestCaseCreate(BaseModel):
    title: str
    scenario_description: str
    target_service: str
    endpoint: str
    priority: Literal["low", "medium", "high", "critical"] = "medium"
    source: Literal["manual", "generated", "scout"] = "manual"
    tags: list[str] = Field(default_factory=list)
    steps: list[TestStep] = Field(default_factory=list)


class TestCase(TestCaseCreate):
    id: str
    status: TestCaseStatus = "active"
    last_run_status: Literal["passed", "failed", "not_run"] = "not_run"
    created_at: datetime
    updated_at: datetime
