-- canonical_transactions: Phase 2's output table, read directly by Phase 3.
--
-- Key design decisions (see phase2-normalization-plan.md and CanonicalTransaction.avsc for the
-- corresponding Avro-side reasoning):
--   * transaction_id is the PRIMARY KEY, not settlement_id -- the split-settlement pair
--     BANK-SPLIT-001-A/-B shares one settlement_id but has two distinct transaction_ids. Using
--     settlement_id as the key would let the second split event silently overwrite the first.
--   * event_id has a UNIQUE constraint as a second idempotency safety net, in case Phase 1's
--     dedup-before-publish guarantee turns out to be weaker than assumed.
--   * amount_value is NUMERIC, not FLOAT/DOUBLE -- money must never be stored as binary floating
--     point (rounding errors accumulate silently in a reconciliation engine, which is the one
--     thing this whole project exists to catch).
--   * event_timestamp is TIMESTAMPTZ, matching the Avro logicalType timestamp-millis field --
--     no string-timestamp parsing anywhere downstream.
--   * status (raw) and canonical_status (normalized) are both stored -- raw is kept for
--     traceability/debugging an unmapped status value, canonical_status is what Phase 3 actually
--     queries against.

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

-- settlement_id is not unique (that's the whole point) -- indexed so Phase 3 can still group
-- split-settlement rows together efficiently.
CREATE INDEX IF NOT EXISTS idx_canonical_transactions_settlement_id
    ON canonical_transactions (settlement_id);

CREATE INDEX IF NOT EXISTS idx_canonical_transactions_merchant_id
    ON canonical_transactions (merchant_id);

CREATE INDEX IF NOT EXISTS idx_canonical_transactions_event_timestamp
    ON canonical_transactions (event_timestamp);
