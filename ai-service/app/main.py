import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path

import psycopg
from dotenv import load_dotenv
from fastapi import FastAPI, Response

from app.config import Settings, defaults, load_settings
from app.logging_setup import configure_logging

# Load settings from ai-service/.env (one level above this package).
load_dotenv(Path(__file__).resolve().parent.parent / ".env")

log = logging.getLogger("app")


def connect() -> psycopg.Connection:
    """Open a connection to Postgres using the POSTGRES_* settings."""
    return psycopg.connect(
        host=os.environ["POSTGRES_HOST"],
        port=os.environ["POSTGRES_PORT"],
        dbname=os.environ["POSTGRES_DB"],
        user=os.environ["POSTGRES_USER"],
        password=os.environ["POSTGRES_PASSWORD"],
        connect_timeout=3,
    )


def prepare_database(conn: psycopg.Connection) -> None:
    """Everything the service needs in Postgres: the pgvector extension and the case table, up to date."""
    from app.jobs import ensure_job_schema

    conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
    ensure_job_schema(conn)


def build_worker(settings: Settings):
    """The background worker wired to the real agent (Gemini reasoner and embedder) and to Phase 5."""
    from app.agent.graph import run_investigation
    from app.agent.reasoning import GeminiReasoner
    from app.delivery import deliver_result
    from app.tools.similar_cases import GeminiEmbedder
    from app.worker import Worker

    reasoner = GeminiReasoner()
    embedder = GeminiEmbedder()

    def investigate(request):
        return run_investigation(request, reasoner=reasoner, embedder=embedder)

    def deliver(result):
        deliver_result(result, url=settings.phase5_result_url)

    return Worker(
        connect=connect,
        investigate=investigate,
        deliver=deliver,
        model=reasoner.model,
        max_concurrent=settings.max_concurrent_investigations,
        max_pickups=settings.max_pickups,
        poll_seconds=settings.worker_poll_seconds,
        backoff_base=settings.backoff_base_seconds,
        backoff_cap=settings.backoff_cap_seconds,
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Any invalid setting stops startup here, with every problem listed at once.
    settings = load_settings()
    configure_logging(
        settings.log_level,
        secrets=[settings.gemini_api_key.get_secret_value(), settings.postgres_password.get_secret_value()],
    )
    app.state.settings = settings
    with connect() as conn:
        prepare_database(conn)

    worker = None
    if settings.worker_enabled:
        worker = build_worker(settings)
        worker.recover()
        worker.start()
        if not settings.phase5_result_url:
            log.warning("PHASE5_RESULT_URL is not set: results will wait as AWAITING_DELIVERY")
    app.state.worker = worker
    log.info("ai-service started (worker %s)", "on" if worker else "off")
    try:
        yield
    finally:
        if worker is not None:
            worker.stop()


app = FastAPI(title="ReconIQ AI Service", lifespan=lifespan)


def current_settings() -> Settings:
    return getattr(app.state, "settings", None) or defaults()


def check_database() -> dict:
    """Report whether Postgres is reachable and which pgvector version is enabled."""
    try:
        with connect() as conn:
            row = conn.execute(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'"
            ).fetchone()
        return {"status": "UP", "pgvector": row[0] if row else "MISSING"}
    except psycopg.Error as exc:
        return {"status": "DOWN", "error": type(exc).__name__}


def read_case_stats():
    """Case counts, or None if they can't be read (e.g. the case table doesn't exist yet)."""
    from app.jobs import case_stats

    try:
        with connect() as conn:
            return case_stats(conn, failed_window_seconds=current_settings().health_failed_window_seconds)
    except psycopg.Error:
        return None


@app.get("/health")
def health(response: Response) -> dict:
    """UP, DEGRADED (works, but results are piling up or failures spiking) or DOWN (503)."""
    db = check_database()
    if db["status"] != "UP" or db.get("pgvector") == "MISSING":
        response.status_code = 503
        return {"status": "DOWN", "database": db}

    settings = current_settings()
    stats = read_case_stats()
    reasons = []
    body = {"database": db}
    if stats is not None:
        if stats.backlog >= settings.health_backlog_max:
            reasons.append(f"{stats.backlog} results waiting for Phase 5 (limit {settings.health_backlog_max})")
        oldest = stats.oldest_backlog_seconds
        if oldest is not None and oldest >= settings.health_backlog_max_age_seconds:
            reasons.append(f"oldest undelivered result is {oldest:.0f} s old (limit {settings.health_backlog_max_age_seconds:.0f} s)")
        if stats.failed_recent >= settings.health_failed_spike:
            reasons.append(
                f"{stats.failed_recent} FAILED results in the last {settings.health_failed_window_seconds:.0f} s "
                f"(limit {settings.health_failed_spike})"
            )
        body.update(deliveries_pending=stats.backlog, failed_recent=stats.failed_recent)
    worker = getattr(app.state, "worker", None)
    body["worker"] = "on" if worker is not None else "off"
    body["status"] = "DEGRADED" if reasons else "UP"
    if reasons:
        body["reasons"] = reasons
    return body


@app.get("/metrics")
def metrics(response: Response) -> dict:
    """Counts of cases and results (from the case table) plus the worker's counts since start."""
    stats = read_case_stats()
    worker = getattr(app.state, "worker", None)
    since_start = worker.counters.snapshot() if worker is not None else {}
    if stats is None:
        response.status_code = 503
        return {"error": "case table unavailable", "worker_since_start": since_start}
    return {
        "cases_received": sum(stats.by_status.values()),
        "cases_by_stage": stats.by_status,
        "results": {s: stats.results_by_status.get(s, 0) for s in ("RESOLVED", "LOW_CONFIDENCE", "FAILED")},
        "failed_by_reason": stats.failed_by_reason,
        "deliveries_pending": stats.backlog,
        "oldest_pending_seconds": stats.oldest_backlog_seconds,
        "gemini_reasoning_calls": stats.reasoning_calls,
        "average_passes_per_case": stats.average_passes,
        "worker_since_start": since_start,
    }


from app.investigate import router  # noqa: E402

app.include_router(router)
