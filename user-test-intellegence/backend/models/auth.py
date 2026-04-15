from typing import Literal

from pydantic import BaseModel


Role = Literal["viewer", "qa", "admin"]


class LoginRequest(BaseModel):
    username: str


class UserSession(BaseModel):
    username: str
    display_name: str
    role: Role
    token: str
