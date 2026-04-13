"""
JWT authentication middleware.
In dev mode (AUTH_BYPASS=true), injects a mock admin principal.
In prod, validates JPMC SSO Bearer tokens.
"""
from __future__ import annotations

import logging
from typing import Optional

from fastapi import HTTPException, Security, Depends, Request
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
import jwt

logger = logging.getLogger(__name__)

_bearer = HTTPBearer(auto_error=False)


class Principal:
    def __init__(self, user_id: str, roles: list[str], team_id: str = "default"):
        self.user_id = user_id
        self.roles = roles
        self.team_id = team_id

    @property
    def is_admin(self) -> bool:
        return "csip:admin" in self.roles

    @property
    def is_operator(self) -> bool:
        return any(r in self.roles for r in ("csip:admin", "csip:operator"))

    @property
    def is_engineer(self) -> bool:
        return any(r in self.roles for r in ("csip:admin", "csip:engineer"))


def get_current_user(
    request: Request,
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer),
) -> Principal:
    from core.config import get_settings
    settings = get_settings()

    if settings.auth_bypass:
        return Principal(user_id="dev-user", roles=["csip:admin"], team_id="platform-ai")

    if not credentials:
        raise HTTPException(status_code=401, detail="Missing bearer token")

    try:
        payload = jwt.decode(
            credentials.credentials,
            settings.jwt_secret,
            algorithms=[settings.jwt_algorithm],
            audience=settings.jwt_audience,
        )
        roles = payload.get("roles", [])
        return Principal(
            user_id=payload.get("sub", "unknown"),
            roles=roles,
            team_id=payload.get("team_id", "default"),
        )
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token expired")
    except jwt.InvalidTokenError as exc:
        raise HTTPException(status_code=401, detail=f"Invalid token: {exc}")


def require_role(required_role: str):
    """FastAPI dependency factory that enforces a minimum role."""
    role_hierarchy = {
        "csip:viewer": 0,
        "csip:operator": 1,
        "csip:engineer": 2,
        "csip:admin": 3,
    }

    def _check(principal: Principal = Depends(get_current_user)) -> Principal:
        user_level = max(
            (role_hierarchy.get(r, -1) for r in principal.roles), default=-1
        )
        required_level = role_hierarchy.get(required_role, 999)
        if user_level < required_level:
            raise HTTPException(
                status_code=403,
                detail=f"Role '{required_role}' required",
            )
        return principal

    return _check
