from __future__ import annotations

from fastapi import APIRouter, Depends

from core.auth import get_current_user, login
from models.auth import LoginRequest, UserSession

router = APIRouter(prefix="/api/v1/auth", tags=["auth"])


@router.post("/login", response_model=UserSession)
def login_route(payload: LoginRequest) -> UserSession:
    return login(payload)


@router.get("/me", response_model=UserSession)
def me_route(user: UserSession = Depends(get_current_user)) -> UserSession:
    return user
