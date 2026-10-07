import hashlib
import json

import psycopg
from fastapi import HTTPException
from psycopg.types.json import Json

from app.contracts import InvestigationRequest

# Intake row only. A later worker can claim status='accepted'.
# No locking, retries, or outbox here.
SCHEMA_SQL = """
CREATE SCHEMA IF NOT EXISTS investigation;

CREATE TABLE IF NOT EXISTS investigation.cases (
    case_id text PRIMARY KEY,
    source_phase text NOT NULL,
    transaction_ids jsonb NOT NULL,
    context jsonb NOT NULL,
    content_hash text NOT NULL,
    status text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
"""


def content_hash(request: InvestigationRequest) -> str:
    payload = {
        "case_id": request.case_id,
        "source_phase": request.source_phase,
        "transaction_ids": request.transaction_ids,
        "context": request.context,
    }
    raw = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def ensure_schema(conn: psycopg.Connection) -> None:
    conn.execute(SCHEMA_SQL)


def accept_request(conn: psycopg.Connection, request: InvestigationRequest) -> str:
    """Persist the request, then return accepted or already_received.

    Same case_id with different content raises HTTP 409.
    The table must already exist (see ensure_schema).
    """
    digest = content_hash(request)
    existing = conn.execute(
        """
        SELECT content_hash
        FROM investigation.cases
        WHERE case_id = %s
        """,
        (request.case_id,),
    ).fetchone()
    if existing is not None:
        if existing[0] == digest:
            return "already_received"
        raise HTTPException(status_code=409, detail="CASE_ID_CONFLICT")

    try:
        conn.execute(
            """
            INSERT INTO investigation.cases
                (case_id, source_phase, transaction_ids, context, content_hash, status)
            VALUES (%s, %s, %s, %s, %s, 'accepted')
            """,
            (
                request.case_id,
                request.source_phase,
                Json(request.transaction_ids),
                Json(request.context),
                digest,
            ),
        )
    except psycopg.errors.UniqueViolation:
        conn.rollback()
        existing = conn.execute(
            """
            SELECT content_hash
            FROM investigation.cases
            WHERE case_id = %s
            """,
            (request.case_id,),
        ).fetchone()
        if existing is not None and existing[0] == digest:
            return "already_received"
        raise HTTPException(status_code=409, detail="CASE_ID_CONFLICT")
    return "accepted"
