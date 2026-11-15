"""Prepares an empty Postgres for the test suite (used by CI).

Enables pgvector, creates Phase 2's canonical table with the same DDL Phase 2 uses, and loads the seven sample
rows the tests rely on (the FEE and SPLIT cases and a shared-transaction_id pair, from sample_data.json). Rows that
already exist are left untouched, so running it against a database Phase 2 already filled changes nothing.

Run from ai-service/:  python tests/fixtures/seed_canonical.py
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

from app.main import connect  # noqa: E402

ROWS_FILE = Path(__file__).with_name("canonical_rows.json")

DDL = """
CREATE SCHEMA IF NOT EXISTS normalization;

CREATE TABLE IF NOT EXISTS normalization.canonical_transactions (
    event_id          TEXT PRIMARY KEY,
    transaction_id    TEXT NOT NULL,
    source            TEXT NOT NULL,
    event_timestamp   TIMESTAMPTZ NOT NULL,
    reference_id      TEXT,
    reference_key     TEXT,
    transaction_type  TEXT,
    amount_value      NUMERIC(18,2),
    amount_currency   TEXT,
    status            TEXT,
    canonical_status  TEXT NOT NULL
                      CHECK (canonical_status IN ('COMPLETED', 'FAILED', 'PENDING', 'UNKNOWN')),
    merchant_id       TEXT,
    customer_id       TEXT,
    settlement_id     TEXT,
    description       TEXT,
    processed_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
"""

COLUMNS = (
    "event_id", "transaction_id", "source", "event_timestamp", "reference_id", "reference_key", "transaction_type",
    "amount_value", "amount_currency", "status", "canonical_status", "merchant_id", "customer_id", "settlement_id",
    "description",
)


def main() -> None:
    rows = json.loads(ROWS_FILE.read_text(encoding="utf-8"))
    placeholders = ", ".join(["%s"] * len(COLUMNS))
    with connect() as conn:
        conn.execute("CREATE EXTENSION IF NOT EXISTS vector")
        conn.execute(DDL)
        inserted = 0
        for row in rows:
            cur = conn.execute(
                f"INSERT INTO normalization.canonical_transactions ({', '.join(COLUMNS)}) "
                f"VALUES ({placeholders}) ON CONFLICT (event_id) DO NOTHING",
                tuple(row[c] for c in COLUMNS),
            )
            inserted += cur.rowcount
    print(f"canonical rows: {inserted} inserted, {len(rows) - inserted} already present")


if __name__ == "__main__":
    main()
