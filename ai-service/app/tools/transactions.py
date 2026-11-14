"""Evidence tool: read the canonical rows behind a mismatch from Phase 2's table.

Read-only. Phase 4 never writes to normalization.canonical_transactions.
"""

from typing import Any, Literal, get_args

import psycopg
from psycopg import sql
from psycopg.rows import dict_row

from app.main import connect

LookupKey = Literal["transaction_id", "settlement_id", "reference_key"]
ALLOWED_KEYS: tuple[str, ...] = get_args(LookupKey)

# Explicit columns, so the row shape the agent sees does not change if the table gains a column.
COLUMNS = (
    "event_id",
    "transaction_id",
    "source",
    "transaction_type",
    "amount_value",
    "amount_currency",
    "status",
    "canonical_status",
    "event_timestamp",
    "merchant_id",
    "customer_id",
    "settlement_id",
    "reference_id",
    "reference_key",
    "description",
    "processed_at",
)

# One prepared query per allowed key, built once from the constants above. The column name in the
# WHERE clause therefore never comes from the caller's argument; only the value is bound per call.
_QUERIES = {
    key: sql.SQL(
        "SELECT {columns} FROM normalization.canonical_transactions "
        "WHERE {key} = %s ORDER BY event_timestamp, event_id"
    ).format(
        columns=sql.SQL(", ").join(sql.Identifier(c) for c in COLUMNS),
        key=sql.Identifier(key),
    )
    for key in ALLOWED_KEYS
}


def get_transaction_group(
    key: LookupKey, value: str, conn: psycopg.Connection | None = None
) -> list[dict[str, Any]]:
    """Return every canonical row whose `key` column equals `value`, oldest first.

    key: one of transaction_id, settlement_id, reference_key. Anything else is refused.
    value: matched exactly, always sent as a bound parameter.
    conn: optional open connection to reuse; by default a read-only connection is opened and closed here.

    An empty list means nothing was found yet. It is not an error.
    Database errors are raised unchanged, for the failure path to classify.
    Amounts come back as Decimal and timestamps as timezone-aware datetime.
    """
    if key not in _QUERIES:
        raise ValueError(f"lookup key must be one of {ALLOWED_KEYS}, got {key!r}")
    if not isinstance(value, str):
        raise TypeError(f"lookup value must be a string, got {type(value).__name__}")
    # Postgres text can never contain a NUL byte, so no stored row can match one.
    if "\x00" in value:
        return []

    if conn is not None:
        return _fetch(conn, key, value)
    with connect() as own_conn:
        own_conn.read_only = True
        return _fetch(own_conn, key, value)


def _fetch(conn: psycopg.Connection, key: str, value: str) -> list[dict[str, Any]]:
    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute(_QUERIES[key], (value,))
        return cur.fetchall()
