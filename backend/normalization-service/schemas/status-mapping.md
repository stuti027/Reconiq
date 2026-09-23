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

## Fallback rule

If a mapper encounters a raw status not in this table (e.g. a future `REFUNDED`, `CANCELLED`,
`PENDING` value not yet present in `sample_data.json`):

- Set `canonical_status = "UNKNOWN"`; never default to `COMPLETED` or `FAILED` for an unrecognized
  value.
- Still write the row, with the raw `status` preserved as-is, so it remains queryable.
- Route a copy to the DLQ (`txn.canonical.dlq`) with reason `"unmapped status: <value> for source
  <source>"`, so the gap is surfaced for the mapping table to be updated.

## Updating this table

Add a new row here first, then update the corresponding mapper's switch/case -- never the other way
around. This table is the single source of truth for what mappers are allowed to output.
