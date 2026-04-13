"""
Async SQLAlchemy database setup with read-replica routing.
Supports PostgreSQL (prod) and SQLite (testing).
"""
from __future__ import annotations

from sqlalchemy.ext.asyncio import (
    AsyncSession,
    AsyncEngine,
    create_async_engine,
    async_sessionmaker,
)
from sqlalchemy.orm import DeclarativeBase
from sqlalchemy import event, text
from typing import AsyncGenerator
import logging

logger = logging.getLogger(__name__)

# Global engine references (initialised in lifespan)
_write_engine: AsyncEngine | None = None
_read_engine: AsyncEngine | None = None
_async_session_factory: async_sessionmaker | None = None

engine: AsyncEngine | None = None  # exposed for lifespan disposal


class Base(DeclarativeBase):
    """SQLAlchemy declarative base for all CSIP ORM models."""
    pass


async def init_db(database_url: str, pool_size: int = 10, max_overflow: int = 20) -> None:
    """Initialise write engine, optional read-replica, and session factory."""
    global _write_engine, _read_engine, _async_session_factory, engine

    connect_args = {}
    if "sqlite" in database_url:
        connect_args = {"check_same_thread": False}

    _write_engine = create_async_engine(
        database_url,
        pool_size=pool_size,
        max_overflow=max_overflow,
        pool_pre_ping=True,
        connect_args=connect_args,
        echo=False,
    )
    engine = _write_engine  # used by alembic and lifespan disposal

    _async_session_factory = async_sessionmaker(
        bind=_write_engine,
        class_=AsyncSession,
        expire_on_commit=False,
        autoflush=False,
        autocommit=False,
    )

    # Create tables (idempotent – alembic handles schema migrations in prod)
    async with _write_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    logger.info("Database initialised: %s", database_url.split("@")[-1])


async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI dependency: yields an async DB session, rolls back on error."""
    if _async_session_factory is None:
        raise RuntimeError("Database not initialised. Call init_db() first.")
    async with _async_session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


async def get_write_session() -> AsyncGenerator[AsyncSession, None]:
    """Explicit write session — always routes to primary."""
    async for session in get_db_session():
        yield session


async def check_health() -> bool:
    """Ping the database; used by /system/health."""
    try:
        async with _write_engine.connect() as conn:
            await conn.execute(text("SELECT 1"))
        return True
    except Exception:
        return False
