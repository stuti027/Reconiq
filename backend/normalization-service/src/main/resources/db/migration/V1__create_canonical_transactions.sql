-- Canonical transactions: one row per raw event received from ingestion.
-- Downstream matching reads this table directly.

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

CREATE INDEX IF NOT EXISTS idx_ct_reference_id
    ON normalization.canonical_transactions (reference_id);
CREATE INDEX IF NOT EXISTS idx_ct_reference_key
    ON normalization.canonical_transactions (reference_key);
CREATE INDEX IF NOT EXISTS idx_ct_transaction_id
    ON normalization.canonical_transactions (transaction_id);
CREATE INDEX IF NOT EXISTS idx_ct_settlement_id
    ON normalization.canonical_transactions (settlement_id);
CREATE INDEX IF NOT EXISTS idx_ct_merchant_time
    ON normalization.canonical_transactions (merchant_id, event_timestamp);