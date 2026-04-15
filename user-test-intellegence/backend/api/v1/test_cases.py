from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, HTTPException

from core.auth import get_current_user, require_role
from core.database import utc_now
from core.pii_masker import PIIMasker
from core.repositories import TestCaseRepository
from core.test_data_governor import TestDataGovernor
from models.auth import UserSession
from models.test_case import TestCase, TestCaseCreate

router = APIRouter(prefix="/api/v1/test-cases", tags=["test-cases"])
repo = TestCaseRepository()
governor = TestDataGovernor()
pii_masker = PIIMasker()


@router.get("", response_model=list[TestCase])
def list_test_cases(service: str | None = None, status: str | None = None, _: UserSession = Depends(get_current_user)) -> list[TestCase]:
    return repo.list(service=service, status=status)


@router.get("/{test_case_id}", response_model=TestCase)
def get_test_case(test_case_id: str, _: UserSession = Depends(get_current_user)) -> TestCase:
    test_case = repo.get(test_case_id)
    if test_case is None:
        raise HTTPException(status_code=404, detail="Test case not found")
    return test_case


@router.post("", response_model=TestCase, status_code=201)
def create_test_case(payload: TestCaseCreate, user: UserSession = Depends(get_current_user)) -> TestCase:
    require_role(user, {"qa", "admin"})
    text_values = [payload.title, payload.scenario_description, *payload.tags]
    text_values.extend(step.action for step in payload.steps)
    text_values.extend(step.expected_response for step in payload.steps)

    pii_findings = [finding for value in text_values for finding in pii_masker.find_pii(value)]
    if pii_findings:
        raise HTTPException(status_code=400, detail=f"PII detected in payload: {', '.join(sorted(set(pii_findings)))}")

    governance = governor.validate_strings(text_values)
    if not governance.accepted:
        raise HTTPException(status_code=400, detail=governance.violations)

    return repo.create(payload, f"tc-{uuid.uuid4().hex[:12]}", utc_now())
