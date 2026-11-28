-- Phase 7: read-only database role for reporting-service.
--
-- reconiq_reporting may only SELECT from the schemas the dashboard reads:
--   ingestion (Phase 1), normalization (Phase 2), investigation (Phase 4).
-- It can't insert, update, delete or create anything.
--
-- Run as the team's owner role (reconiq_user), which creates the tables in these schemas.
-- Safe to run more than once. On a running stack:
--   docker exec -i reconiq-postgres psql -U reconiq_user -d reconiq_db < docker/postgres/reporting-readonly.sql
--
-- The password is for local development only, like the rest of the team's docker-compose.yml.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reconiq_reporting') THEN
        CREATE ROLE reconiq_reporting LOGIN PASSWORD 'reporting_readonly_dev';
    END IF;
END
$$;

-- Second safety net: every transaction of this role is read-only, even if a grant is added by mistake.
ALTER ROLE reconiq_reporting SET default_transaction_read_only = on;

GRANT CONNECT ON DATABASE reconiq_db TO reconiq_reporting;

-- Phase 2 (Flyway) and Phase 4 (on start-up) create their schemas only when they first run, which can be after
-- this script. Creating the empty schemas here lets the grants below be given now. Both services use
-- CREATE SCHEMA IF NOT EXISTS, and Flyway accepts an existing empty schema.
CREATE SCHEMA IF NOT EXISTS ingestion;
CREATE SCHEMA IF NOT EXISTS normalization;
CREATE SCHEMA IF NOT EXISTS investigation;

GRANT USAGE ON SCHEMA ingestion, normalization, investigation TO reconiq_reporting;

-- Tables that already exist.
GRANT SELECT ON ALL TABLES IN SCHEMA ingestion, normalization, investigation TO reconiq_reporting;

-- Tables the owner role creates later (e.g. investigation.cases on ai-service's first start).
ALTER DEFAULT PRIVILEGES IN SCHEMA ingestion, normalization, investigation
    GRANT SELECT ON TABLES TO reconiq_reporting;
