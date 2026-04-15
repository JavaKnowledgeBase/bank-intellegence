from datetime import datetime
from typing import Literal

from pydantic import BaseModel


class ScoutObservation(BaseModel):
    id: str
    title: str
    source_url: str
    domain: str
    summary: str
    safety_status: Literal["accepted", "rejected", "needs_review"]
    proposed_service: str
    tags: list[str]
    discovered_at: datetime


class ImprovementProposal(BaseModel):
    id: str
    title: str
    area: str
    summary: str
    expected_impact: str
    status: Literal["proposed", "approved", "rejected", "shadow_mode"]
    created_at: datetime
