# normalization-service (Phase 2: Data Normalization)

Reads raw transaction events published by ingestion, normalizes them into one canonical shape, and
writes that shape to Postgres for the matching phase to read. Anything it cannot process goes to a
dead-letter topic with a reason code, never silently dropped.

## What it does

```
raw-transactions (Kafka, JSON)
        |
        v
  normalizer  --canonical-->  txn.canonical (Avro)  --> writer --> normalization.canonical_transactions
        |                                                    |
        +------------------- txn.canonical.dlq (JSON) <------+
```

- **normalizer**: a Kafka Streams app. Parses each raw message, validates it, maps it into the
  canonical schema, and routes the result: valid records to `txn.canonical`, anything invalid or
  flagged to `txn.canonical.dlq`.
- **writer**: a separate consumer that reads `txn.canonical` and upserts it into Postgres
  idempotently (no lost rows, no duplicates, one bad row never blocks the rest).

The two run in one process by default (`APP_MODE=all`) but can be deployed separately.

## Modes

Set `APP_MODE` to one of:

| Mode | Runs | Needs |
|---|---|---|
| `normalizer` | the Streams app only | Kafka, Schema Registry |
| `writer` | the Postgres writer only | Kafka, Schema Registry, Postgres |
| `all` (default) | both, in one JVM | Kafka, Schema Registry, Postgres |

## Configuration

All settings come from environment variables. Copy `.env.example` to `.env` and fill in the values
for your environment; every setting has a working default for the private sandbox
(`phase2-sandbox/docker-compose.yml`) except passwords. At startup, every configuration problem is
reported together, then the process exits with code `2`.

| Variable | Default | Meaning |
|---|---|---|
| `APP_MODE` | `all` | `normalizer` / `writer` / `all` |
| `KAFKA_HOST`, `KAFKA_PORT` | `localhost`, `9092` | Kafka bootstrap server |
| `SCHEMA_REGISTRY_URL` | `http://localhost:8081` | Avro schema registry |
| `POSTGRES_HOST`, `POSTGRES_PORT` | `localhost`, `5432` | Writer database |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | see `.env.example` | Writer database credentials |
| `INPUT_TOPICS` | `raw-transactions` | Comma-separated list |
| `CANONICAL_TOPIC`, `DLQ_TOPIC` | `txn.canonical`, `txn.canonical.dlq` | Output topics |
| `TOPICS_AUTO_CREATE`, `TOPIC_PARTITIONS`, `TOPIC_REPLICATION` | `true`, `3`, `1` | Startup topic creation |
| `STREAMS_APPLICATION_ID` | `normalization-service` | Kafka Streams application id / consumer group |
| `WRITER_GROUP_ID`, `WRITER_BATCH_SIZE` | `normalization-writer`, `500` | Writer consumer settings |
| `WRITER_CONFLICT_POLICY` | `REPORT` | `REPORT` (do nothing, report conflicts) or `UPDATE` (deliberate backfill) |
| `HEALTH_PORT` | `8090` | `/health` and `/metrics`; `0` disables |
| `DLQ_GUARD_ACTION` | `alarm` | `alarm` (log + `/health` DEGRADED) or `stop` |
| `DLQ_GUARD_WINDOW_SECONDS`, `DLQ_GUARD_PROCESSING_ERRORS` | `60`, `5` | Burst rule |
| `DLQ_GUARD_REJECT_RATIO`, `DLQ_GUARD_MIN_RECORDS` | `0.5`, `100` | Ratio rule |
| `LOG_LEVEL` | `INFO` | Logging |

## Canonical table

`normalization.canonical_transactions` (created by the Flyway migration
`src/main/resources/db/migration/V1__create_canonical_transactions.sql`, applied automatically by the
writer on startup). One row per raw event; primary key `event_id`.

| Column | Type | Notes |
|---|---|---|
| `event_id` | `TEXT` (PK) | Unique per raw event |
| `transaction_id` | `TEXT` | Not unique: a gateway and a bank event for the same transaction share it |
| `source` | `TEXT` | `BANK` / `PAYMENT_GATEWAY` |
| `event_timestamp` | `TIMESTAMPTZ` | When the event happened |
| `reference_id`, `reference_key` | `TEXT` | `reference_key` is upper-cased, non-alphanumerics removed, for fuzzy matching |
| `transaction_type` | `TEXT` | |
| `amount_value` | `NUMERIC(18,2)` | Exact decimal; nullable when the source sent no amount |
| `amount_currency` | `TEXT` | |
| `status`, `canonical_status` | `TEXT` | Raw status as received, plus one of `COMPLETED`/`FAILED`/`PENDING`/`UNKNOWN` |
| `merchant_id`, `customer_id`, `settlement_id` | `TEXT` | `settlement_id` groups split settlements; not unique |
| `description` | `TEXT` | |
| `processed_at` | `TIMESTAMPTZ` | When this service wrote the row; never changed on conflict |

## Status mapping

`(source, raw status) -> canonical_status` is a data file, not code:
`src/main/resources/status-mapping.csv`. See `schemas/status-mapping.md` for the fallback rule and
how to add a new status. Any status not in the file is written as `UNKNOWN` and a copy is sent to the
dead-letter topic, so the gap is visible without losing the row.

## Dead-letter topic (`txn.canonical.dlq`)

JSON text, keyed by the original message key, so it is readable directly in Kafka UI.

```json
{
  "kind": "REJECTED",
  "reasonCode": "INVALID_AMOUNT_SCALE",
  "reasonDetail": "amount.value has more than 2 decimals",
  "originalKey": "TXN00006",
  "originalValue": "<raw message text, exactly as received>",
  "sourceTopic": "raw-transactions",
  "partition": 1,
  "offset": 4711,
  "originalKafkaTimestamp": 1787219280123,
  "failedAt": 1787219281000,
  "app": "normalization-service"
}
```

| Reason code | Kind | Source |
|---|---|---|
| `INVALID_JSON`, `MISSING_EVENT_ID`, `INVALID_TIMESTAMP`, `UNKNOWN_SOURCE`, `MISSING_TRANSACTION`, `MISSING_TRANSACTION_ID`, `INVALID_AMOUNT`, `INVALID_AMOUNT_SCALE` | `REJECTED` | Input parsing |
| `UNMAPPED_STATUS` | `FLAGGED` | Mapping (row is still written, as `UNKNOWN`) |
| `PROCESSING_ERROR` | `REJECTED` | Unexpected exception, caught and logged |
| `DB_REJECTED` | `REJECTED` | Postgres rejected the row (a data error, isolated to that row) |
| `EVENT_ID_CONFLICT` | `REJECTED` | Same `event_id` already stored with different content; the first row is kept |

## Health and metrics

`GET /health` returns `200 {"status":"UP",...}` or `503 {"status":"DEGRADED"|"DOWN",...}`.
`GET /metrics` returns a JSON snapshot of counters (`records_in`, `canonical_out`, `rejected` by
reason, `flagged`, `duplicates_skipped`, `conflicts`, `db_rejected`, `db_retries`,
`batches_committed`, per-source counts).

## Running

**Locally, without Docker:**
```bash
mvn package
java -jar target/normalization-service-<version>.jar
```

**With Docker** (image build is optional; the JAR runs directly with `java -jar` day to day):
```bash
docker build -t normalization-service .
docker run --env-file .env normalization-service
```

**Tests:**
```bash
mvn test
```
Requires Docker running (Testcontainers starts a throwaway `postgres:16` for the database tests).

## Working assumptions

Decided without asking Phase 1 or Phase 3 during the build; each is additive, so a wrong guess is
cheap to correct in review:

- Phase 1's numeric (epoch-seconds) timestamp format is intentional and stable; the parser also
  accepts ISO-8601 text as a fallback.
- The canonical timestamp is the envelope's `event_timestamp`, not the inner
  `data.transaction.timestamp` (equal in the sample data).
- Phase 3 queries the canonical table directly; the extra columns beyond its stated field list
  (`event_id`, `source`, `canonical_status`, `description`, `reference_key`, `processed_at`) are
  additive and safe to ignore if unused.
- `reference_key` (upper-cased, non-alphanumerics removed) is provided for fuzzy matching; the raw
  `reference_id` is also kept.
- No ledger/CDC input exists yet on `main`; the topology has a clean extension point
  (`SourceMapper`/`MapperRegistry`) for a future `LedgerMapper`, but none is built.

## Local development

A private sandbox stack (`phase2-sandbox/docker-compose.yml`, one directory above this repository)
provides Kafka, Schema Registry, Postgres and Kafka UI for solo development. To run this service
against the team's shared stack instead, add a Schema Registry to it with
`docker-compose.schema-registry.yml` in this directory.
