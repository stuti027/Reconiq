-- Schema initialization for Phase 1: Ingestion
CREATE SCHEMA IF NOT EXISTS ingestion;

-- Table to store raw incoming transaction events
CREATE TABLE IF NOT EXISTS ingestion.raw_transaction_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    source VARCHAR(50) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    
    -- Transaction core details
    transaction_id VARCHAR(100) NOT NULL,
    reference_id VARCHAR(100),
    settlement_id VARCHAR(100),
    amount_value NUMERIC(15, 2), -- Nullable for missing amount payloads
    amount_currency VARCHAR(10),
    status VARCHAR(50) NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    
    -- Entities
    merchant_id VARCHAR(100) NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    description TEXT,
    
    -- Ingestion Metadata
    raw_payload JSONB NOT NULL,
    ingested_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Audit log to ensure idempotency and prevent duplicate event processing
CREATE TABLE IF NOT EXISTS ingestion.ingestion_audit_log (
    audit_id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(100) UNIQUE NOT NULL,
    source VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL, -- e.g., 'INGESTED', 'DUPLICATE', 'FAILED'
    processed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    error_message TEXT
);

-- Fast lookup indexes
CREATE INDEX idx_raw_events_txn_id ON ingestion.raw_transaction_events(transaction_id);
CREATE INDEX idx_raw_events_ref_id ON ingestion.raw_transaction_events(reference_id);
CREATE INDEX idx_raw_events_source ON ingestion.raw_transaction_events(source);
CREATE INDEX idx_raw_events_timestamp ON ingestion.raw_transaction_events(event_timestamp);