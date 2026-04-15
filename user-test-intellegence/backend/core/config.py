from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "CTIP"
    environment: str = "development"
    api_prefix: str = "/api/v1"
    host: str = "0.0.0.0"
    port: int = 8091
    frontend_origin: str = "http://localhost:3200"
    database_path: Path = Path(__file__).resolve().parents[1] / "data" / "ctip.db"
    database_url: str | None = None
    evidence_dir: Path = Path(__file__).resolve().parents[1] / "data" / "evidence"
    execution_mode: str = "local"
    task_queue_mode: str = "eager"
    celery_broker_url: str | None = None
    celery_result_backend: str | None = None
    search_backend_mode: str = "auto"
    elasticsearch_url: str | None = None
    elasticsearch_index_name: str = "ctip-search"
    shard_size: int = 20
    service_urls_json: str = (
        '{"customer-agent-service":"http://localhost:8081",'
        '"fraud-detection-service":"http://localhost:8082",'
        '"loan-prescreen-service":"http://localhost:8083"}'
    )

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def resolved_database_url(self) -> str:
        if self.database_url:
            return self.database_url
        return f"sqlite+pysqlite:///{self.database_path.as_posix()}"

    @property
    def resolved_celery_broker_url(self) -> str:
        return self.celery_broker_url or "redis://localhost:6379/0"

    @property
    def resolved_celery_result_backend(self) -> str:
        return self.celery_result_backend or self.resolved_celery_broker_url

    @property
    def resolved_elasticsearch_url(self) -> str:
        return self.elasticsearch_url or "http://localhost:9200"


settings = Settings()
