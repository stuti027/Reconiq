# Phase 7 API contract (`reporting-service`)

Version 1, 28 Nov 2026. Frontend (Person B) and backend (Person A) both build against this file.
Change it only by agreement, and bump the version line when you do.

- Base URL: `http://localhost:8095` (env `VITE_API_URL` in the frontend).
- Every Phase 7 path starts with `/api/reporting`.
- All responses are JSON (`Content-Type: application/json`).
- Numbers in the examples are illustrative, except the `evt_gw_curr_001` row (2.7, 2.8), which is the real sample event.

---

## 1. Rules that apply to every endpoint

### 1.1 Money
```json
{ "value": "1051.32", "currency": "INR" }
```
- `value` is a **string** with 2 decimals, never a JSON number (numbers lose precision in JavaScript).
- Amounts in different currencies are **never added together**. Totals are always per currency.
- A row whose source has no amount sends `"amount": null`.

### 1.2 Time
- Instants are ISO-8601 UTC with `Z`: `"2026-08-20T09:48:00Z"`. The UI converts to IST.
- Calendar days (e.g. per-day charts) are IST dates: `"2026-08-20"`.
- Durations are whole seconds as a number: `"medianSeconds": 540`.
- Query filters `from` / `to` are ISO-8601 UTC instants; `from` is inclusive, `to` is exclusive.

### 1.3 Data that doesn't exist yet
A value that can't be computed is replaced by this object, **never by `0` or `null`**:
```json
{ "available": false, "reason": "PHASE3_RESULTS_NOT_STORED" }
```
Any field marked `T | Unavailable` below can hold either its normal value or this object.
The frontend checks `value?.available === false`.

| Reason | Meaning |
|---|---|
| `PHASE3_RESULTS_NOT_STORED` | Phase 3 matches but doesn't store results yet |
| `PHASE5_NOT_PERSISTED` | Phase 5 has no database yet |
| `PHASE5_LIST_MISSING` | Phase 5 has no endpoint that lists cases |
| `PHASE6_NOT_BUILT` | Phase 6 output doesn't exist yet |
| `NO_DATA_YET` | The source exists but has no rows, so a rate would divide by zero |
| `SERVICE_DOWN` | The service holding the data didn't answer |

### 1.4 Counts and rates
- Counts are JSON integers.
- Rates are numbers from 0 to 1 (`0.67`). The UI shows them as percentages.
- Grouped counts are lists, ordered by count (largest first) unless stated otherwise:
  `[{ "key": "BANK", "count": 120 }]`.

### 1.5 Lists with paging
Request: `?page=0&size=25` (page starts at 0; `size` default 25, max 100).
```json
{ "items": [ ... ], "page": 0, "size": 25, "total": 197 }
```

### 1.6 Errors
```json
{ "error": "Unknown sort column: amountt" }
```
| Status | When |
|---|---|
| 400 | Bad query parameter (unknown sort column, bad date, size > 100) |
| 403 | Role not allowed (from part 8.8 on) |
| 404 | The id doesn't exist |
| 409 | Phase 5 says the case was already decided |
| 422 | Phase 5 rejected the input (passed on as its message) |
| 502 | A service this call depends on answered with an error |
| 504 | A service this call depends on didn't answer in time |

### 1.7 Who is calling (dev mode)
- With `AUTH_MODE=dev`, the frontend sends `X-Dev-User: <name>|<ROLE>`, e.g. `X-Dev-User: asha|REVIEWER`.
- Roles: `VIEWER`, `REVIEWER`, `ADMIN`.
- From part 7.10 the header is replaced by `Authorization: Bearer <token>`. Endpoints don't change.

---

## 2. Endpoints (Person A)

### 2.1 `GET /api/reporting/ping` (7.0.2)
```json
{ "status": "ok" }
```

### 2.2 `GET /api/reporting/overview` (7.1.1)
```json
{
  "eventsIngested": 197,
  "canonicalRows": 197,
  "unmappedStatuses": 0,
  "casesBySourcePhase": [{ "key": "PHASE_3", "count": 2 }],
  "resultsByStatus": [{ "key": "RESOLVED", "count": 2 }],
  "agentResolutionRate": 1.0,
  "deliveryBacklog": { "count": 0, "oldestAgeSeconds": null },
  "failedLast24h": 0,
  "transactionsPerDay": [{ "day": "2026-08-20", "count": 41 }],
  "matchRate": { "available": false, "reason": "PHASE3_RESULTS_NOT_STORED" },
  "closedCases": { "available": false, "reason": "PHASE5_NOT_PERSISTED" },
  "generatedAt": "2026-11-28T10:00:00Z"
}
```
| Field | Type | Source |
|---|---|---|
| `eventsIngested` | int | `count(*)` of `ingestion.raw_transaction_events` (through `IngestionSource`) |
| `canonicalRows` | int | `count(*)` of `normalization.canonical_transactions` |
| `unmappedStatuses` | int | canonical rows with `canonical_status = 'UNKNOWN'` |
| `casesBySourcePhase` | list | `investigation.cases` grouped by `source_phase` |
| `resultsByStatus` | list | `result->>'status'` where `result IS NOT NULL` |
| `agentResolutionRate` | number \| Unavailable | RESOLVED ÷ (RESOLVED + LOW_CONFIDENCE + FAILED); `NO_DATA_YET` if no results |
| `deliveryBacklog.count` | int | cases with `status = 'AWAITING_DELIVERY'` |
| `deliveryBacklog.oldestAgeSeconds` | int \| null | `now() - min(finished_at)` of those; `null` when count is 0 |
| `failedLast24h` | int | `result->>'status' = 'FAILED'` and `finished_at > now() - 24 h` |
| `transactionsPerDay` | list of `{day, count}` | by IST day of `event_timestamp`, oldest first |
| `matchRate` | number \| Unavailable | Phase 3 (7.6.1) |
| `closedCases` | int \| Unavailable | Phase 5 (7.6.2) |

### 2.3 `GET /api/reporting/funnel` (7.1.1)
Stages in pipeline order:
```json
{
  "stages": [
    { "key": "INGESTED",     "label": "Ingested",          "count": 197 },
    { "key": "NORMALIZED",   "label": "Normalized",        "count": 197 },
    { "key": "MATCHED",      "label": "Matched",           "count": { "available": false, "reason": "PHASE3_RESULTS_NOT_STORED" } },
    { "key": "INVESTIGATED", "label": "Sent to the agent", "count": 2 },
    { "key": "RESOLVED",     "label": "Resolved by agent", "count": 2 },
    { "key": "CLOSED",       "label": "Closed",            "count": { "available": false, "reason": "PHASE5_NOT_PERSISTED" } }
  ]
}
```
`count` is `int | Unavailable`. The UI draws unavailable stages dashed.

### 2.4 `GET /api/reporting/health` (7.1.2)
```json
{
  "services": [
    { "phase": 1, "name": "ingestion",             "status": "UNKNOWN",  "reason": "NO_HEALTH_ENDPOINT", "checkedAt": "2026-11-28T10:00:00Z", "details": null },
    { "phase": 2, "name": "normalization-service", "status": "DEGRADED", "reason": null,                 "checkedAt": "2026-11-28T10:00:00Z", "details": { } },
    { "phase": 3, "name": "reconciliation-service","status": "UNKNOWN",  "reason": "NO_HEALTH_ENDPOINT", "checkedAt": "2026-11-28T10:00:00Z", "details": null },
    { "phase": 4, "name": "ai-service",            "status": "UP",       "reason": null,                 "checkedAt": "2026-11-28T10:00:00Z", "details": { } },
    { "phase": 5, "name": "resolution-service",    "status": "DOWN",     "reason": "TIMEOUT",            "checkedAt": "2026-11-28T10:00:00Z", "details": null },
    { "phase": 6, "name": "anomaly-detection",     "status": "NOT_BUILT","reason": null,                 "checkedAt": "2026-11-28T10:00:00Z", "details": null }
  ],
  "cacheSeconds": 10
}
```
- `status`: `UP` | `DEGRADED` | `DOWN` | `UNKNOWN` | `NOT_BUILT`.
- `reason` when not `UP`: `NO_HEALTH_ENDPOINT`, `TIMEOUT` (no answer in 2 s), `CONNECTION_REFUSED`, `BAD_RESPONSE`, or the service's own reason.
- `details`: the service's own health body, passed through unchanged (Phase 2 `:8090/health`, Phase 4 `:8000/health`, Phase 5 `:8085/actuator/health`).
- Phase 2 answers **503 for DEGRADED and DOWN**, and Phase 4 answers 503 for DOWN. The body is read on 503 too, so 503 does not automatically mean `DOWN`.
- This endpoint itself always returns 200, even when every service is down.

### 2.5 `GET /api/reporting/runtime-counters` (7.1.2, Admin only from 8.8)
```json
{
  "phase2": { "scope": "since_restart", "metrics": { } },
  "phase4": { "scope": "mostly_stored", "metrics": { } }
}
```
- `metrics` is that service's `/metrics` body, unchanged.
- If a service doesn't answer, its entry is `{ "available": false, "reason": "SERVICE_DOWN" }`.

### 2.6 `GET /api/reporting/transactions/stats?from&to` (7.2.1)
```json
{
  "bySource": [{ "key": "BANK", "count": 110 }],
  "byCanonicalStatus": [{ "key": "COMPLETED", "count": 150 }],
  "amountByCurrency": [
    { "currency": "INR", "count": 180, "total": { "value": "254300.50", "currency": "INR" } },
    { "currency": "USD", "count": 3,   "total": { "value": "300.00",    "currency": "USD" } }
  ],
  "withoutAmount": 2,
  "topMerchants": [{ "key": "MER003", "count": 40 }],
  "normalizationLatency": { "medianSeconds": 1, "maxSeconds": 4 }
}
```
- `topMerchants`: at most 10.
- `withoutAmount`: rows where `amount_value IS NULL` (left out of `amountByCurrency`).
- `normalizationLatency`: `processed_at - ingested_at`, canonical joined to raw on `event_id`. It's an Unavailable (`NO_DATA_YET`) if no rows join.

### 2.7 `GET /api/reporting/transactions` (7.2.1)
Query (all optional):

| Param | Meaning |
|---|---|
| `source` | `BANK`, `PAYMENT_GATEWAY`, ... (exact) |
| `merchant` | `merchant_id` (exact) |
| `status` | `canonical_status`: `COMPLETED`, `FAILED`, `PENDING`, `UNKNOWN` |
| `currency` | `amount_currency` (exact) |
| `from`, `to` | on `event_timestamp` |
| `q` | text contained in `event_id` or `transaction_id` |
| `page`, `size` | see 1.5 |
| `sort` | `<column>,<asc\|desc>`; columns: `eventTimestamp`, `amount`, `merchantId`, `source`, `canonicalStatus`. Default `eventTimestamp,desc`. Anything else is 400. |

Each item:
```json
{
  "eventId": "evt_gw_curr_001",
  "transactionId": "TXN-CURR-001",
  "source": "PAYMENT_GATEWAY",
  "eventTimestamp": "2026-08-23T05:00:00Z",
  "transactionType": "PAYMENT",
  "amount": { "value": "100.00", "currency": "USD" },
  "status": "SUCCESS",
  "canonicalStatus": "COMPLETED",
  "merchantId": "MER003",
  "settlementId": "SET-CURR-001"
}
```

### 2.8 `GET /api/reporting/transactions/{eventId}` (7.2.1)
```json
{
  "canonical": {
    "eventId": "evt_gw_curr_001",
    "transactionId": "TXN-CURR-001",
    "source": "PAYMENT_GATEWAY",
    "eventTimestamp": "2026-08-23T05:00:00Z",
    "referenceId": "REF-CURR-001",
    "referenceKey": "REF-CURR-001",
    "transactionType": "PAYMENT",
    "amount": { "value": "100.00", "currency": "USD" },
    "status": "SUCCESS",
    "canonicalStatus": "COMPLETED",
    "merchantId": "MER003",
    "customerId": "CUST1012",
    "settlementId": "SET-CURR-001",
    "description": "...",
    "processedAt": "2026-11-13T16:00:01Z"
  },
  "raw": {
    "eventType": "transaction.created",
    "ingestedAt": "2026-11-13T16:00:00Z",
    "rawPayload": { }
  },
  "matchResults": { "available": false, "reason": "PHASE3_RESULTS_NOT_STORED" }
}
```
- The lookup and the raw join are on **`event_id`**, never `transaction_id` (not unique: 81 of 197 sample events share one).
- 404 if there's no canonical row with that `event_id`.
- `raw` is `{ "available": false, "reason": "RAW_EVENT_NOT_FOUND" }` if Phase 1 has no row for it.
- `rawPayload` is Phase 1's `raw_payload` JSON, unchanged.

### 2.9 `GET /api/reporting/reconciliation/summary` (7.2.1, switched on in 7.6.1)
Until Phase 3 stores results:
```json
{ "available": false, "reason": "PHASE3_RESULTS_NOT_STORED" }
```
The available shape is written in 7.6.1, once Phase 3's results table exists.

---

## 3. Endpoints (Person B)

Person B may refine these shapes. Update this file when you do.

### 3.1 `GET /api/reporting/investigations/stats` (7.4.1)
```json
{
  "byStage": [{ "key": "DELIVERED", "count": 2 }],
  "byResult": [{ "key": "RESOLVED", "count": 2 }],
  "failuresByReason": [{ "key": "POISON_CASE", "count": 0 }],
  "endToEnd": { "medianSeconds": 540, "maxSeconds": 610 },
  "pastCasesStored": 2
}
```
- `byStage`: `status` of `investigation.cases` (`RECEIVED`, `IN_PROGRESS`, `AWAITING_DELIVERY`, `DELIVERED`, `FAILED`).
- `endToEnd`: `finished_at - received_at`; Unavailable (`NO_DATA_YET`) if no case has finished.

### 3.2 `GET /api/reporting/investigations?status&result&sourcePhase&page&size` (7.4.1)
Each item:
```json
{
  "caseId": "int-001",
  "sourcePhase": "PHASE_3",
  "status": "DELIVERED",
  "resultStatus": "RESOLVED",
  "attempts": 1,
  "receivedAt": "2026-11-13T15:50:00Z",
  "finishedAt": "2026-11-13T15:59:00Z",
  "deliveredAt": "2026-11-13T15:59:01Z"
}
```
`resultStatus` is `null` while the case has no result.

### 3.3 `GET /api/reporting/investigations/{caseId}` (7.4.1)
```json
{
  "caseId": "int-001",
  "sourcePhase": "PHASE_3",
  "transactionIds": ["TXN-FEE-001"],
  "status": "DELIVERED",
  "result": {
    "status": "RESOLVED",
    "rootCause": "...",
    "citedEvidence":    [{ "kind": "transaction", "refId": "evt_bank_fee_001" }],
    "examinedEvidence": [{ "kind": "transaction", "refId": "evt_bank_fee_001" }, { "kind": "past_case", "refId": "case-07" }],
    "failureReason": null,
    "attempts": 1,
    "model": "gemini-3.8-flash",
    "investigatedAt": "2026-11-13T15:59:00Z"
  },
  "receivedAt": "2026-11-13T15:50:00Z",
  "finishedAt": "2026-11-13T15:59:00Z",
  "deliveredAt": "2026-11-13T15:59:01Z",
  "deliveryAttempts": 1,
  "lastError": null
}
```
- `result` comes from the `result` jsonb (Phase 4's `InvestigationResult`, snake_case there, camelCase here). `null` if there's no result yet.
- `kind` is `transaction` or `past_case`.

### 3.4 `GET /api/reporting/anomalies/summary` (7.4.1)
```json
{
  "casesFromAnomalies": 0,
  "anomalies": { "available": false, "reason": "PHASE6_NOT_BUILT" }
}
```
`casesFromAnomalies` = cases with `source_phase = 'PHASE_6'`.

### 3.5 `GET /api/reporting/review/queue?page&size` (7.5.1)
Until Phase 5 has a list endpoint: `{ "available": false, "reason": "PHASE5_LIST_MISSING" }`.
Otherwise a paged list, oldest first. Each item:
```json
{
  "caseId": "int-001",
  "state": "PENDING_REVIEW",
  "discrepancy": { "value": "12.50", "currency": "INR" },
  "reason": "Currency mismatch",
  "waitingSeconds": 3600,
  "rootCause": "...",
  "citedEvidence": [{ "kind": "transaction", "refId": "evt_bank_fee_001" }]
}
```

### 3.6 `GET /api/reporting/review/{caseId}/history` (7.5.1)
Phase 5's `GET /api/v1/workflows/{caseId}/history` body, passed through unchanged.

### 3.7 `POST /api/reporting/review/{caseId}/approve | reject | reopen` (7.5.1, Reviewer only)
Request bodies (the reviewer is **never** in the body; it comes from the logged-in user):
```json
{ "action": "FEE_WRITE_OFF", "notes": "Bank fee confirmed" }
{ "notes": "Evidence doesn't support the root cause", "closeImmediately": false }
{ "reason": "Needs a second look" }
```
- `action`: `FEE_WRITE_OFF`, `LEDGER_ADJUSTMENT`, `RETRY_SETTLEMENT`, `MANUAL_ESCALATION`.
- `notes` is required to reject.
- Response: Phase 5's case snapshot, unchanged.
- Phase 5's 409 becomes `{ "error": "Already decided by someone else" }`; its 422 is passed on.

---

## 4. Phase 8 endpoints used by the frontend (Person B)

| Endpoint | Part | Shape |
|---|---|---|
| `GET /api/auth/me` | 8.3 | `{ "subject", "issuer", "authType", "username", "roles": ["REVIEWER"] }` |
| `GET /api/admin/audit?from&to&eventType&actor&source&page&size` | 8.5 | Paged list (1.5). Item shape: Person B sends it on Sat 5 Dec. |
| `POST /api/admin/audit/verify` | 8.6 | `{ "checked": 120, "passed": 120, "firstFailedId": null }` |
