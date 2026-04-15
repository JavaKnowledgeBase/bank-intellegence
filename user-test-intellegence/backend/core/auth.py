from __future__ import annotations

from fastapi import Header, HTTPException

from models.auth import LoginRequest, UserSession


USERS = {
    "olivia.viewer": {"display_name": "Olivia Viewer", "role": "viewer"},
    "quinn.qa": {"display_name": "Quinn QA", "role": "qa"},
    "avery.admin": {"display_name": "Avery Admin", "role": "admin"},
}


def build_session(username: str) -> UserSession:
    user = USERS.get(username)
    if user is None:
        raise HTTPException(status_code=401, detail="Unknown user")
    return UserSession(
        username=username,
        display_name=user["display_name"],
        role=user["role"],
        token=f"ctip-local-{username}",
    )


def login(payload: LoginRequest) -> UserSession:
    return build_session(payload.username)


def get_current_user(authorization: str | None = Header(default=None)) -> UserSession:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing bearer token")
    token = authorization.removeprefix("Bearer ")
    if not token.startswith("ctip-local-"):
        raise HTTPException(status_code=401, detail="Invalid token")
    username = token.removeprefix("ctip-local-")
    return build_session(username)


def require_role(user: UserSession, allowed: set[str]) -> UserSession:
    if user.role not in allowed:
        raise HTTPException(status_code=403, detail="Insufficient role")
    return user
