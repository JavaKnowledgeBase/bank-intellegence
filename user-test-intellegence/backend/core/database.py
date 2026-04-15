from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from sqlalchemy import create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from core.config import settings
from core.db_models import Base


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Database:
    def __init__(self, database_url: str, path: Path):
        self.database_url = database_url
        self.path = path
        if self.database_url.startswith("sqlite"):
            self.path.parent.mkdir(parents=True, exist_ok=True)
        connect_args = {"check_same_thread": False} if self.database_url.startswith("sqlite") else {}
        self.engine: Engine = create_engine(self.database_url, future=True, connect_args=connect_args)
        self.SessionLocal = sessionmaker(bind=self.engine, autoflush=False, autocommit=False, future=True)

    @contextmanager
    def session(self) -> Iterator[Session]:
        session = self.SessionLocal()
        try:
            yield session
            session.commit()
        finally:
            session.close()

    def init_db(self) -> None:
        Base.metadata.create_all(self.engine)

    def _prepare(self, query: str, params: tuple[Any, ...] | list[Any] | dict[str, Any] = ()) -> tuple[str, dict[str, Any]]:
        if isinstance(params, dict):
            return query, params
        prepared_query = query
        values: dict[str, Any] = {}
        for index, value in enumerate(params):
            key = f"p{index}"
            prepared_query = prepared_query.replace("?", f":{key}", 1)
            values[key] = value
        return prepared_query, values

    def count(self, table: str) -> int:
        row = self.fetchone(f"SELECT COUNT(*) AS total FROM {table}")
        return int(row["total"] if row else 0)

    def execute(self, query: str, params: tuple[Any, ...] | list[Any] | dict[str, Any] = ()) -> None:
        prepared_query, values = self._prepare(query, params)
        with self.engine.begin() as connection:
            connection.execute(text(prepared_query), values)

    def fetchall(self, query: str, params: tuple[Any, ...] | list[Any] | dict[str, Any] = ()) -> list[dict[str, Any]]:
        prepared_query, values = self._prepare(query, params)
        with self.engine.connect() as connection:
            rows = connection.execute(text(prepared_query), values).mappings().all()
        return [dict(row) for row in rows]

    def fetchone(self, query: str, params: tuple[Any, ...] | list[Any] | dict[str, Any] = ()) -> dict[str, Any] | None:
        prepared_query, values = self._prepare(query, params)
        with self.engine.connect() as connection:
            row = connection.execute(text(prepared_query), values).mappings().first()
        return dict(row) if row else None


db = Database(settings.resolved_database_url, settings.database_path)
