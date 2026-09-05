# Per-source status -> canonical_status mapping

Referenced by `canonical-transaction.avsc`'s `canonical_status` field. Each `SourceMapper`
implementation (`GatewayMapper`, `BankMapper`, later `LedgerMapper`) must consult this table when
setting `canonical_status` -- never hand-roll a mapping inline in mapper code.

canonical_status is always one of: `COMPLETED`, `FAILED`, `PENDING`, `UNKNOWN`.

| source           | raw status (from sample data) | canonical_status |
|------------------|-------------------------------|-------------------|
| PAYMENT_GATEWAY  | SUCCESS                       | COMPLETED         |
| PAYMENT_GATEWAY  | FAILED                        | FAILED            |
| BANK             | SETTLED                       | COMPLETED         |
| BANK             | FAILED                        | FAILED            |

## Fallback rule (not cut, per the no-shortcuts sprint plan)

If a mapper encounters a raw status not in this table (e.g. a future `REFUNDED`, `CANCELLED`,
`PENDING` value that hasn't shown up in `sample_data.json` yet):

- Set `canonical_status = "UNKNOWN"` -- never silently default to `COMPLETED` or `FAILED`, since
  guessing wrong on a reconciliation engine's own status field is worse than flagging it.
- Still write the row (don't drop it) with the raw `status` preserved as-is, so it's queryable and
  debuggable.
- Also route a copy to the DLQ (`txn.canonical.dlq`) with the reason `"unmapped status: <value> for
  source <source>"`, so it surfaces as a real signal to fix the mapping table, not just a silent
  UNKNOWN sitting in Postgres.

## Updating this table

Add a new row here first, then update the corresponding mapper's switch/case -- never the other way
around. This table is the single source of truth for what mappers are allowed to output.
