"""
CSIP Configuration — Pydantic Settings with environment variable binding.
Supports dev (env vars) and prod (Vault) modes.
"""
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field
from typing import Optional
from functools import lru_cache


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ── Core ──────────────────────────────────────────────────────────────────
    environment: str = Field(default="development", alias="ENVIRONMENT")
    version: str = "2.0.0"
    port: int = Field(default=8092, alias="CSIP_PORT")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    # ── Vault (prod) ──────────────────────────────────────────────────────────
    vault_addr: str = Field(default="http://localhost:8200", alias="VAULT_ADDR")
    vault_token: Optional[str] = Field(default=None, alias="VAULT_TOKEN")
    vault_bypass: bool = Field(default=True, alias="VAULT_BYPASS")  # dev: read from env

    # ── Database ──────────────────────────────────────────────────────────────
    database_url: str = Field(
        default="postgresql+asyncpg://cibap_user:changeme@localhost:5432/cibap",
        alias="DATABASE_URL",
    )
    database_pool_size: int = 10
    database_max_overflow: int = 20

    # ── Redis ─────────────────────────────────────────────────────────────────
    redis_url: str = Field(default="redis://localhost:6379", alias="REDIS_URL")
    redis_password: Optional[str] = Field(default=None, alias="REDIS_PASSWORD")

    # ── Kafka ─────────────────────────────────────────────────────────────────
    kafka_bootstrap_servers: str = Field(
        default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS"
    )
    kafka_consumer_group: str = "csip-monitoring"
    kafka_auto_offset_reset: str = "latest"

    # ── LLM / Anthropic ───────────────────────────────────────────────────────
    anthropic_api_key: Optional[str] = Field(default=None, alias="ANTHROPIC_API_KEY")
    claude_model: str = "claude-sonnet-4-6"
    llm_max_retries: int = 3
    llm_timeout_seconds: int = 60

    # ── Vector Store ──────────────────────────────────────────────────────────
    vector_store_backend: str = Field(
        default="chromadb", alias="VECTOR_STORE_BACKEND"
    )  # "chromadb" | "pinecone"
    pinecone_api_key: Optional[str] = Field(default=None, alias="PINECONE_API_KEY")
    pinecone_environment: str = Field(default="us-east-1", alias="PINECONE_ENVIRONMENT")
    pinecone_index_name: str = "csip-logs"
    chromadb_path: str = Field(default="./data/chromadb", alias="CHROMADB_PATH")

    # ── GitHub ────────────────────────────────────────────────────────────────
    github_token: Optional[str] = Field(default=None, alias="GITHUB_TOKEN")
    github_base_url: str = "https://api.github.com"

    # ── Observability ─────────────────────────────────────────────────────────
    otel_endpoint: str = Field(
        default="http://localhost:4317", alias="OTEL_ENDPOINT"
    )
    otel_enabled: bool = Field(default=False, alias="OTEL_ENABLED")

    # ── Security / Auth ───────────────────────────────────────────────────────
    jwt_secret: str = Field(default="dev-secret-change-in-prod", alias="JWT_SECRET")
    jwt_algorithm: str = "HS256"
    jwt_audience: str = "csip-api"
    auth_bypass: bool = Field(default=True, alias="AUTH_BYPASS")  # dev: skip auth

    # ── PagerDuty ─────────────────────────────────────────────────────────────
    pagerduty_integration_key: Optional[str] = Field(
        default=None, alias="PAGERDUTY_INTEGRATION_KEY"
    )

    # ── AWS ───────────────────────────────────────────────────────────────────
    aws_region: str = Field(default="us-east-1", alias="AWS_REGION")
    s3_audit_bucket: str = Field(default="csip-audit-logs", alias="S3_AUDIT_BUCKET")
    s3_enabled: bool = Field(default=False, alias="S3_ENABLED")

    # ── LLM Budgets (daily token limits) ─────────────────────────────────────
    budget_rca_agent: int = 500_000
    budget_code_fix_agent: int = 300_000
    budget_log_analyst: int = 200_000
    budget_discovery: int = 50_000
    budget_self_improver: int = 100_000

    # ── Noise Reduction ───────────────────────────────────────────────────────
    dedup_window_seconds: int = 300        # 5-minute suppression window
    max_error_patterns_per_window: int = 20
    semantic_dedup_threshold: float = 0.88
    min_error_count_for_rca: int = 3       # Don't fire RCA for 1-2 occurrences
    rca_confidence_threshold: float = 0.85 # Min confidence to attempt code fix

    # ── Fix Pipeline ──────────────────────────────────────────────────────────
    fix_max_attempts: int = 3
    fix_cooldown_seconds: int = 3600  # 1 hour between fix attempts per service
    sandbox_timeout_seconds: int = 900

    # ── Self-Improver ─────────────────────────────────────────────────────────
    self_improver_cron: str = "0 1 * * *"  # 1 AM UTC daily
    self_improver_enabled: bool = True

    @property
    def is_production(self) -> bool:
        return self.environment == "production"

    @property
    def is_development(self) -> bool:
        return self.environment == "development"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
