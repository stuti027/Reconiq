# reporting-service (Phase 7)

Read-only REST API behind the ReconIQ dashboard. Port **8095**. The API is defined in
[`docs/phase7-api-contract.md`](../../docs/phase7-api-contract.md).

## Run it

Needs JDK 17 or newer and Maven 3.9+.

```
cd backend/reporting-service
mvn package
java -jar target/reporting-service-0.1.0-SNAPSHOT.jar
```

Check: `curl http://localhost:8095/api/reporting/ping` returns `{"status":"ok"}`.

The service starts without a database. Endpoints that query Postgres fail until it is up.

## Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `REPORTING_PORT` | `8095` | HTTP port |
| `REPORTING_DB_URL` | `jdbc:postgresql://127.0.0.1:5432/reconiq_db` | Team database |
| `REPORTING_DB_USER` | `reconiq_reporting` | Read-only role |
| `REPORTING_DB_PASSWORD` | `reporting_readonly_dev` | Its password (local development only) |
| `PHASE2_URL`, `PHASE4_URL`, `PHASE5_URL` | `http://127.0.0.1:8090`, `:8000`, `:8085` | Services whose health and counters are shown |
| `AUTH_MODE` | `dev` | `dev`: user from the `X-Dev-User: name|ROLE` header. `keycloak`: token login (part 8.8; not built yet, so the service won't start in this mode). |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Browser origins allowed to call the API (comma-separated) |

## Code layout

| Package | Owner | Holds |
|---|---|---|
| `common/` | A | Shared types used by every endpoint: `Money`, `Available`, `PageResult`, errors, `CurrentUser` |
| `config/` | A | CORS, dev-mode user |
| `overview/`, `health/`, `transactions/`, `audit/` | A | Pages 1, 2, 5 |
| `investigations/`, `review/` | B | Pages 3, 4 |
| `security/` | B (8.8) | Keycloak token login |

## Read-only database role

`docker/postgres/reporting-readonly.sql` (repo root) creates `reconiq_reporting` with SELECT only on the
`ingestion`, `normalization` and `investigation` schemas, including tables created later. On a running team stack:

```
docker exec -i reconiq-postgres psql -U reconiq_user -d reconiq_db < docker/postgres/reporting-readonly.sql
```

## Docker

```
docker build -t reconiq/reporting-service backend/reporting-service
docker run -p 8095:8095 -e REPORTING_DB_URL=jdbc:postgresql://host.docker.internal:5432/reconiq_db reconiq/reporting-service
```

The image runs as a non-root user (uid 10001).
