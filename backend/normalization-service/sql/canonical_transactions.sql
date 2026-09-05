-- canonical_transactions: Phase 2 output table, read directly by Phase 3.
--
-- Design notes:
--   * transaction_id is the PRIMARY KEY, not settlement_id. A split settlement (one settlement
--     producing multiple transaction records) shares one settlement_id across distinct
--     transaction_ids; keying on settlement_id would let one split record overwrite another.
--   * event_id has a UNIQUE constraint as a secondary idempotency check, independent of Phase 1's
--     own dedup guarantee.
--   * amount_value is NUMERIC, not FLOAT/DOUBLE, to avoid floating-point rounding error in
--     monetary values.
--   * event_timestamp is TIMESTAMPTZ, matching the Avro timestamp-millis logical type.
--   * Both status (raw, source-reported) and canonical_status (normalized) are stored: raw for
--     traceability, canonical_status for downstream querying by Phase 3.

CREATE TABLE IF NOT EXISTS canonical_transactions (
    transaction_id      TEXT PRIMARY KEY,
    event_id            TEXT NOT NULL UNIQUE,
    reference_id        TEXT,
    source              TEXT NOT NULL,
    transaction_type    TEXT,
    amount_value         NUMERIC(18, 2) NOT NULL,
    amount_currency      TEXT NOT NULL,
    status              TEXT NOT NULL,
    canonical_status    TEXT NOT NULL,
    event_timestamp      TIMESTAMPTZ NOT NULL,
    merchant_id         TEXT,
    customer_id         TEXT,
    settlement_id       TEXT,
    processed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- settlement_id is intentionally not unique; indexed so Phase 3 can group split-settlement
-- rows together efficiently.
CREATE INDEX IF NOT EXISTS idx_canonical_transactions_settlement_id
    ON canonical_transactions (settlement_id);

CREATE INDEX IF NOT EXISTS idx_canonical_transactions_merchant_id
    ON canonical_transactions (merchant_id);

CREATE INDEX IF NOT EXISTS idx_canonical_transactions_event_timestamp
    ON canonical_transactions (event_timestamp);
