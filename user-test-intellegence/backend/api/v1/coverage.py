from __future__ import annotations

from collections import defaultdict

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from core.repositories import TestCaseRepository
from models.auth import UserSession

router = APIRouter(prefix="/api/v1/coverage", tags=["coverage"])
repo = TestCaseRepository()


@router.get("")
def get_coverage(_: UserSession = Depends(get_current_user)) -> dict:
    matrix: dict[str, dict[str, int]] = defaultdict(dict)
    cases = repo.list()
    for test_case in cases:
        matrix[test_case.target_service][test_case.endpoint] = (
            matrix[test_case.target_service].get(test_case.endpoint, 0) + 1
        )
    rows = [
        {
            "service": service,
            "endpoints": endpoint_map,
            "coverage_gaps": [endpoint for endpoint, count in endpoint_map.items() if count < 2],
        }
        for service, endpoint_map in matrix.items()
    ]
    return {"rows": rows}
