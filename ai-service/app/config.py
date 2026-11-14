"""Every setting the service reads, checked together at startup.

A missing or invalid environment variable is collected with all the others and reported in one ConfigError, so a
first deployment with several mistakes is fixed in one pass, not one restart per mistake. Empty values count as
unset. The Gemini key is held as a secret, so it never appears in a repr or a log line.
"""

import os
from typing import Literal, Mapping

from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError, model_validator


class ConfigError(RuntimeError):
    """One or more settings are missing or invalid. `problems` lists each one as 'NAME: reason'."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = problems
        super().__init__("invalid configuration:\n  " + "\n  ".join(problems))


class Settings(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    postgres_host: str = Field(min_length=1)
    postgres_port: int = Field(ge=1, le=65535)
    postgres_db: str = Field(min_length=1)
    postgres_user: str = Field(min_length=1)
    postgres_password: SecretStr

    gemini_api_key: SecretStr
    gemini_model: str = Field(min_length=1)
    gemini_embedding_model: str = Field(min_length=1)

    # Empty until Phase 5 exists; results then wait as AWAITING_DELIVERY.
    phase5_result_url: str | None = None

    worker_enabled: bool = True
    max_concurrent_investigations: int = Field(default=2, ge=1, le=32)
    max_pickups: int = Field(default=3, ge=1)
    worker_poll_seconds: float = Field(default=2.0, gt=0)
    backoff_base_seconds: float = Field(default=1.0, gt=0)
    backoff_cap_seconds: float = Field(default=60.0, gt=0)

    health_backlog_max: int = Field(default=50, ge=1)
    health_backlog_max_age_seconds: float = Field(default=600.0, gt=0)
    health_failed_spike: int = Field(default=10, ge=1)
    health_failed_window_seconds: float = Field(default=600.0, gt=0)

    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    @model_validator(mode="after")
    def _cap_not_below_base(self) -> "Settings":
        if self.backoff_cap_seconds < self.backoff_base_seconds:
            raise ValueError("BACKOFF_CAP_SECONDS must not be below BACKOFF_BASE_SECONDS")
        return self


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    """Read Settings from environment variables (field name in upper case). Raises ConfigError listing every problem."""
    env = os.environ if env is None else env
    values = {}
    for name in Settings.model_fields:
        raw = env.get(name.upper())
        if raw is not None and raw.strip() != "":
            values[name] = raw.strip()
    try:
        return Settings(**values)
    except ValidationError as exc:
        problems = []
        for error in exc.errors():
            field = ".".join(str(part) for part in error["loc"]).upper() or "SETTINGS"
            problems.append(f"{field}: {error['msg']}")
        raise ConfigError(problems) from None


def defaults() -> Settings:
    """The default values of the optional settings, for code that runs before startup (e.g. tests)."""
    return Settings.model_construct()
