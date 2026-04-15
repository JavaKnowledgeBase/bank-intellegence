from __future__ import annotations

from pathlib import Path

from sqlalchemy import inspect

from core.config import settings
from core.database import db

MANAGED_TABLES = {
    "test_cases",
    "suite_runs",
    "shard_runs",
    "scout_observations",
    "improvement_proposals",
    "execution_events",
}


def ensure_database_schema() -> None:
    if settings.resolved_database_url.startswith("sqlite"):
        settings.database_path.parent.mkdir(parents=True, exist_ok=True)

    try:
        from alembic import command
        from alembic.config import Config
    except Exception:
        db.init_db()
        return

    backend_root = Path(__file__).resolve().parents[1]
    config = Config(str(backend_root / "alembic.ini"))
    config.set_main_option("script_location", str(backend_root / "db_alembic"))
    config.set_main_option("sqlalchemy.url", settings.resolved_database_url)

    existing_tables = set(inspect(db.engine).get_table_names())
    version_row = None
    if "alembic_version" in existing_tables:
        version_row = db.fetchone("SELECT version_num FROM alembic_version LIMIT 1")
        if version_row and version_row.get("version_num"):
            command.upgrade(config, "head")
            return
    if existing_tables & MANAGED_TABLES:
        command.stamp(config, "head")
        return
    command.upgrade(config, "head")
