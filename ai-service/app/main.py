import os
from pathlib import Path

import psycopg
from dotenv import load_dotenv
from fastapi import FastAPI

# Load settings from ai-service/.env (one level above this package).
load_dotenv(Path(__file__).resolve().parent.parent / ".env")

app = FastAPI(title="ReconIQ AI Service")


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


@app.get("/health")
def health() -> dict:
    db = check_database()
    healthy = db["status"] == "UP" and db.get("pgvector") != "MISSING"
    return {"status": "UP" if healthy else "DOWN", "database": db}


from app.investigate import router  # noqa: E402

app.include_router(router)
