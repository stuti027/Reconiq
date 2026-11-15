"""The case lifecycle in investigation.cases: claim, finish, deliver, and recover after a crash.

    RECEIVED --claim--> IN_PROGRESS --result stored--> AWAITING_DELIVERY --2xx from Phase 5--> DELIVERED
        ^                    |
        +--- release --------+   (environment error: Postgres or Gemini down; the case is not at fault)

The stored result is the outbox: it stays AWAITING_DELIVERY until Phase 5 acknowledges it. `pickup_count` counts
claims that did not finish; after a crash the startup sweep puts IN_PROGRESS rows back, and a case picked up
`max_pickups` times without finishing becomes FAILED POISON_CASE instead of looping forever.

Every function takes an open connection; the caller's `with connect() as conn:` commits.
"""

from dataclasses import dataclass, field

import psycopg
from psycopg.types.json import Json

from app.contracts import InvestigationRequest
from app.failures import POISON_CASE
from app.result import InvestigationResult, failed_result

RECEIVED = "RECEIVED"
IN_PROGRESS = "IN_PROGRESS"
AWAITING_DELIVERY = "AWAITING_DELIVERY"
DELIVERED = "DELIVERED"

# Brings a table created before the lifecycle existed up to date. Run once at startup, not per request:
# ALTER TABLE takes a lock even when the column is already there.
MIGRATION_SQL = """
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS pickup_count integer NOT NULL DEFAULT 0;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS result jsonb;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS failure_reason text;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS finished_at timestamptz;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS delivery_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS next_delivery_at timestamptz;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS last_error text;
ALTER TABLE investigation.cases ADD COLUMN IF NOT EXISTS delivered_at timestamptz;
UPDATE investigation.cases SET status = 'RECEIVED' WHERE status = 'accepted';
CREATE INDEX IF NOT EXISTS cases_status_received_idx ON investigation.cases (status, received_at);
"""

_REQUEST_COLUMNS = "c.case_id, c.source_phase, c.transaction_ids, c.context, c.pickup_count"


@dataclass(frozen=True)
class ClaimedCase:
    request: InvestigationRequest
    pickup_count: int


@dataclass(frozen=True)
class PendingDelivery:
    case_id: str
    result: InvestigationResult
    delivery_attempts: int


@dataclass(frozen=True)
class RecoveryReport:
    reset: list[str] = field(default_factory=list)
    poisoned: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class CaseStats:
    by_status: dict[str, int]
    results_by_status: dict[str, int]
    failed_by_reason: dict[str, int]
    backlog: int
    oldest_backlog_seconds: float | None
    failed_recent: int
    reasoning_calls: int
    average_passes: float | None


def ensure_job_schema(conn: psycopg.Connection) -> None:
    """Create the table if missing (intake's DDL) and add any lifecycle column an older table lacks."""
    from app.request_store import ensure_schema

    ensure_schema(conn)
    conn.execute(MIGRATION_SQL)


def claim_cases(conn: psycopg.Connection, limit: int) -> list[ClaimedCase]:
    """Atomically take up to `limit` RECEIVED cases, oldest first, and mark them IN_PROGRESS.

    SKIP LOCKED means two workers can never take the same case; each claim adds one to pickup_count.
    """
    rows = conn.execute(
        f"""
        WITH picked AS (
            SELECT case_id FROM investigation.cases
            WHERE status = %s
            ORDER BY received_at, case_id
            LIMIT %s
            FOR UPDATE SKIP LOCKED
        )
        UPDATE investigation.cases c
        SET status = %s, pickup_count = c.pickup_count + 1, updated_at = now()
        FROM picked
        WHERE c.case_id = picked.case_id
        RETURNING {_REQUEST_COLUMNS}, c.received_at
        """,
        (RECEIVED, limit, IN_PROGRESS),
    ).fetchall()
    rows.sort(key=lambda r: (r[5], r[0]))
    return [ClaimedCase(_request(row), row[4]) for row in rows]


def release_case(conn: psycopg.Connection, case_id: str) -> bool:
    """Put a claimed case back as RECEIVED and undo its pickup: an outage is not the case's fault."""
    cur = conn.execute(
        """
        UPDATE investigation.cases
        SET status = %s, pickup_count = GREATEST(pickup_count - 1, 0), updated_at = now()
        WHERE case_id = %s AND status = %s
        """,
        (RECEIVED, case_id, IN_PROGRESS),
    )
    return cur.rowcount == 1


def store_result(conn: psycopg.Connection, result: InvestigationResult) -> bool:
    """Save a finished result on its row (the outbox) and mark it AWAITING_DELIVERY, due now.

    Only an IN_PROGRESS row is updated, so a result can't overwrite one already stored. Returns whether it was saved.
    """
    cur = conn.execute(
        """
        UPDATE investigation.cases
        SET status = %s, result = %s, failure_reason = %s, finished_at = now(), updated_at = now(),
            delivery_attempts = 0, next_delivery_at = now(), last_error = NULL
        WHERE case_id = %s AND status = %s
        """,
        (AWAITING_DELIVERY, Json(result.model_dump(mode="json")), result.failure_reason, result.case_id, IN_PROGRESS),
    )
    return cur.rowcount == 1


def recover_after_crash(conn: psycopg.Connection, *, max_pickups: int, model: str) -> RecoveryReport:
    """Startup sweep. Rows left IN_PROGRESS (the process died mid-case) go back to RECEIVED, unless they have
    already been picked up max_pickups times: those become FAILED POISON_CASE, ready for delivery.

    Assumes one service process: it would also reset cases another live process is working on.
    """
    rows = conn.execute(
        f"""
        SELECT {_REQUEST_COLUMNS} FROM investigation.cases c
        WHERE c.status = %s
        ORDER BY c.received_at, c.case_id
        FOR UPDATE
        """,
        (IN_PROGRESS,),
    ).fetchall()
    report = RecoveryReport()
    for row in rows:
        request, pickups = _request(row), row[4]
        if pickups >= max_pickups:
            store_result(conn, failed_result(request, failure_reason=POISON_CASE, model=model))
            report.poisoned.append(request.case_id)
        else:
            conn.execute(
                "UPDATE investigation.cases SET status = %s, updated_at = now() WHERE case_id = %s",
                (RECEIVED, request.case_id),
            )
            report.reset.append(request.case_id)
    return report


def due_deliveries(conn: psycopg.Connection, limit: int) -> list[PendingDelivery]:
    """Results waiting for Phase 5 whose next try is due, longest-waiting first."""
    rows = conn.execute(
        """
        SELECT case_id, result, delivery_attempts FROM investigation.cases
        WHERE status = %s AND next_delivery_at <= now()
        ORDER BY next_delivery_at, case_id
        LIMIT %s
        """,
        (AWAITING_DELIVERY, limit),
    ).fetchall()
    return [PendingDelivery(r[0], InvestigationResult.model_validate(r[1]), r[2]) for r in rows]


def mark_delivered(conn: psycopg.Connection, case_id: str) -> bool:
    cur = conn.execute(
        """
        UPDATE investigation.cases
        SET status = %s, delivered_at = now(), updated_at = now(), last_error = NULL
        WHERE case_id = %s AND status = %s
        """,
        (DELIVERED, case_id, AWAITING_DELIVERY),
    )
    return cur.rowcount == 1


def mark_delivery_failed(conn: psycopg.Connection, case_id: str, *, delay_seconds: float, error: str) -> None:
    """Keep the result waiting and schedule the next try `delay_seconds` from now."""
    conn.execute(
        """
        UPDATE investigation.cases
        SET delivery_attempts = delivery_attempts + 1,
            next_delivery_at = now() + make_interval(secs => %s),
            last_error = %s, updated_at = now()
        WHERE case_id = %s AND status = %s
        """,
        (delay_seconds, error[:500], case_id, AWAITING_DELIVERY),
    )


def case_stats(conn: psycopg.Connection, *, failed_window_seconds: float) -> CaseStats:
    """Counts for /health and /metrics."""
    by_status = dict(conn.execute("SELECT status, count(*) FROM investigation.cases GROUP BY status").fetchall())
    results = conn.execute(
        """
        SELECT result->>'status', count(*), coalesce(sum((result->>'attempts')::int), 0)
        FROM investigation.cases WHERE result IS NOT NULL GROUP BY 1
        """
    ).fetchall()
    failed_by_reason = dict(
        conn.execute(
            """
            SELECT failure_reason, count(*) FROM investigation.cases
            WHERE result->>'status' = 'FAILED' GROUP BY 1
            """
        ).fetchall()
    )
    backlog, oldest = conn.execute(
        """
        SELECT count(*), extract(epoch FROM now() - min(finished_at))
        FROM investigation.cases WHERE status = %s
        """,
        (AWAITING_DELIVERY,),
    ).fetchone()
    failed_recent = conn.execute(
        """
        SELECT count(*) FROM investigation.cases
        WHERE result->>'status' = 'FAILED' AND finished_at > now() - make_interval(secs => %s)
        """,
        (failed_window_seconds,),
    ).fetchone()[0]

    reasoned = [(n, calls) for status, n, calls in results if status in ("RESOLVED", "LOW_CONFIDENCE")]
    reasoned_cases = sum(n for n, _ in reasoned)
    return CaseStats(
        by_status=by_status,
        results_by_status={status: n for status, n, _ in results},
        failed_by_reason=failed_by_reason,
        backlog=backlog,
        oldest_backlog_seconds=float(oldest) if oldest is not None else None,
        failed_recent=failed_recent,
        reasoning_calls=sum(int(calls) for _, _, calls in results),
        average_passes=(sum(int(c) for _, c in reasoned) / reasoned_cases) if reasoned_cases else None,
    )


def _request(row) -> InvestigationRequest:
    return InvestigationRequest(case_id=row[0], source_phase=row[1], transaction_ids=row[2], context=row[3])
