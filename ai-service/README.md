# ai-service: Phase 4 (agentic investigation)

Investigates the mismatches rule-based matching can't explain. Phase 3 (or Phase 6) sends a case; the service
gathers evidence from Phase 2's canonical transactions and from similar past cases, asks Gemini to explain the
mismatch while citing that evidence, looks once more if the answer isn't well supported, and sends one result per
case to Phase 5: `RESOLVED`, `LOW_CONFIDENCE` or `FAILED`. It explains *why*; Phase 5 decides *what to do*.

**Nothing is prebuilt.** Every command below builds or installs from this folder.

## How a case flows

```
POST /investigate ──> investigation.cases (saved before the 202 reply)   status RECEIVED
                         │ worker claims it (FOR UPDATE SKIP LOCKED)        IN_PROGRESS
                         v
                 agent graph: gather evidence -> Gemini reasons -> code checks -> (one directed retry) -> finalize
                         │ result stored on the row (the outbox)          AWAITING_DELIVERY
                         v
                 POST result to Phase 5, retried with backoff until 2xx   DELIVERED
```

- Postgres or Gemini down, rate-limited (429), busy (5xx) or misconfigured (bad key), or Phase 2's table not created
  yet: the case is put back and the worker waits, longer each time. These never become `FAILED`.
- Model output invalid twice, model refused, or an unexpected error in our code: `FAILED` with a reason code
  (`LLM_OUTPUT_INVALID`, `LLM_REFUSED`, `PROCESSING_ERROR`).
- A case picked up 3 times without finishing (it crashed the service each time): `FAILED POISON_CASE`.
- Phase 5 unreachable or answering non-2xx: the result waits as `AWAITING_DELIVERY` and is retried.
- After a crash or restart, cases left `IN_PROGRESS` go back to `RECEIVED` automatically.

## Configuration

Copy `.env.example` to `.env` (never committed) and fill it in. All settings are checked together at startup; if
any is missing or invalid, the service lists every problem and does not start.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | yes | | The Postgres holding Phase 2's `normalization.canonical_transactions`. Use `127.0.0.1`, not `localhost` (IPv6 is tried first and waits 3 s) |
| `GEMINI_API_KEY` | yes | | Your key from aistudio.google.com |
| `GEMINI_MODEL` | yes | | e.g. `gemini-3.8-flash` |
| `GEMINI_EMBEDDING_MODEL` | yes | | `gemini-embedding-2` (768 dimensions) |
| `PHASE5_RESULT_URL` | no | empty | Phase 5's results endpoint. Empty: deliveries are off and results wait as `AWAITING_DELIVERY` until it is set and the service restarts |
| `WORKER_ENABLED` | no | `true` | `false` runs only the HTTP endpoints (no investigations) |
| `MAX_CONCURRENT_INVESTIGATIONS` | no | `2` | Cases investigated at once (also limits Gemini traffic) |
| `MAX_PICKUPS` | no | `3` | Pickups without finishing before `POISON_CASE` |
| `WORKER_POLL_SECONDS` | no | `2` | Idle wait between checks for new cases and due deliveries |
| `BACKOFF_BASE_SECONDS`, `BACKOFF_CAP_SECONDS` | no | `1`, `60` | Retry waits: doubling from base, capped, with jitter; `Retry-After` respected |
| `HEALTH_BACKLOG_MAX`, `HEALTH_BACKLOG_MAX_AGE_SECONDS` | no | `50`, `600` | `/health` is `DEGRADED` when this many results wait, or the oldest is this old |
| `HEALTH_FAILED_SPIKE`, `HEALTH_FAILED_WINDOW_SECONDS` | no | `10`, `600` | `/health` is `DEGRADED` at this many `FAILED` results within the window |
| `LOG_LEVEL` | no | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

Log lines carry `case=<case_id>`. The Gemini key and the database password are masked if they ever appear.

## Run locally

From `ai-service/`, with the repository's virtual environment (Python 3.13.7, `Reconiq/.venv`; create it once
from the repository root with `py -3.13 -m venv .venv`):

```
..\.venv\Scripts\python.exe -m pip install -r requirements.txt
..\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000
```

The database needs Phase 2's canonical table and rows. On an empty database (e.g. a fresh pgvector container),
`python tests/fixtures/seed_canonical.py` creates the table with Phase 2's DDL and loads the sample rows the tests
use. The service creates its own `investigation` schema and enables pgvector at startup. Don't run the seed
against the team stack's database: there Phase 2 creates the table itself.

## Run with Docker

```
docker build -f DockerFile -t reconiq-ai-service .
docker run --rm -p 8000:8000 --env-file .env -e POSTGRES_HOST=host.docker.internal reconiq-ai-service
```

Note the file is `DockerFile` (capital F), so pass `-f DockerFile`. The image runs as a non-root user, has a
Docker health check on `/health`, and never contains `.env`.

## Run with the team stack

From the repository root, without editing the team's `docker-compose.yml`:

```
docker compose -f docker-compose.yml -f ai-service/docker-compose.ai-service.yml up -d --build
```

The override swaps the `postgres` image for `pgvector/pgvector:0.8.7-pg16-trixie` and adds `ai-service` on
`reconiq-net`, port 8000. **Start Postgres on a fresh volume** when switching image: the pgvector image runs on a
different Linux base than `postgres:16-alpine`, and text indexes on the old volume can silently sort differently.

## Endpoints

| Endpoint | What it returns |
|---|---|
| `POST /investigate` | `202 {"status": "accepted"}` once the case is saved; `202 already_received` for an identical resend; `409 CASE_ID_CONFLICT` for the same `case_id` with different content; `422` for an invalid body |
| `GET /health` | `200 UP`, `200 DEGRADED` (with `reasons`: Phase 2's table missing, results piling up, oldest too old, or `FAILED` spiking), or `503 DOWN` (Postgres unreachable or pgvector missing) |
| `GET /metrics` | Cases by stage, results by status, `FAILED` by reason, deliveries pending, Gemini reasoning calls, average passes per case, and the worker's counts since start |

Request body: `{"case_id": "...", "source_phase": "PHASE_3" | "PHASE_6", "transaction_ids": ["..."], "context": {}}`.
The result sent to Phase 5 is `InvestigationResult` in `app/result.py` (`schema_version` 1).

## Tests

From `ai-service/`, with Postgres running:

```
..\.venv\Scripts\python.exe -m unittest discover -s tests
```

Real-Gemini tests are skipped unless `RUN_GEMINI_TESTS=1` is set (they spend API quota, and the free tier can
answer `503`, which means "try later", not a failure). CI (`.github/workflows/ai-service.yml`) runs the suite on a
fresh pgvector database seeded with `tests/fixtures/seed_canonical.py`, without a Gemini key.

## What this service assumes about other phases

| | Assumption |
|---|---|
| C-1 (Phase 6) | One request is one case; Phase 6 sends N requests if it wants N verdicts |
| C-2 (Phase 3) | Either the unmatched side's ids or all linked ids may be sent; when only one side arrives, the agent's second pass finds the rest by `settlement_id` / `reference_key` |
| C-3 (Phases 3, 6) | `context` is optional and untyped; nothing depends on it |
| C-4 (Phase 5) | Results are POSTed to `PHASE5_RESULT_URL`; a stub receiver stands in until Phase 5 exists |
| C-5 (Phase 5) | Phase 5 ignores a `case_id` it already has: delivery is at-least-once, so a result can arrive twice after a crash |
| C-6 (Phase 5) | Phase 5 doesn't need a severity field; it derives what it needs from the result and the rows |
