from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user
from models.auth import UserSession
from services.search_index import SearchIndexService

router = APIRouter(prefix="/api/v1/search", tags=["search"])
service = SearchIndexService()


@router.get("")
def search(
    query: str = "",
    service_name: str | None = None,
    kind: str | None = None,
    status: str | None = None,
    level: str | None = None,
    sort: str = "relevance",
    limit: int = 30,
    _: UserSession = Depends(get_current_user),
) -> dict:
    return service.search(
        query=query,
        service=service_name,
        kind=kind,
        status=status,
        level=level,
        sort=sort,
        limit=limit,
    )


@router.get("/analytics")
def analytics(_: UserSession = Depends(get_current_user)) -> dict:
    return service.analytics()


@router.post("/sync")
def sync_index(_: UserSession = Depends(get_current_user)) -> dict:
    return service.sync_index()
