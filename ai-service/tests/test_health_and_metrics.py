"""/health (UP, DEGRADED, DOWN with 503), /metrics, and app startup (settings check, schema, worker switch)."""

import logging
import os
import unittest
from unittest import mock

import psycopg
from fastapi.testclient import TestClient

from app.config import ConfigError, load_settings
from app.contracts import InvestigationRequest
from app.jobs import claim_cases, ensure_job_schema, store_result
from app.main import app, connect
from app.result import failed_result
from tests.test_job_lifecycle import delete_test_rows, insert_case


def settings_with(**overrides):
    env = dict(os.environ)
    env.update({k.upper(): str(v) for k, v in overrides.items()})
    return load_settings(env)


def finish_failed(case_id: str) -> None:
    request = InvestigationRequest(case_id=case_id, source_phase="PHASE_3", transaction_ids=["TXN-FEE-001"])
    with connect() as conn:
        claim_cases(conn, 1)
        store_result(conn, failed_result(request, failure_reason="LLM_REFUSED", model="m"))


class HealthTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with connect() as conn:
            ensure_job_schema(conn)

    def setUp(self):
        delete_test_rows()
        self.client = TestClient(app)
        self.saved_settings = getattr(app.state, "settings", None)

    def tearDown(self):
        delete_test_rows()
        app.state.settings = self.saved_settings

    def test_up(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "UP")
        self.assertEqual(body["database"]["status"], "UP")

    def test_database_down_is_503(self):
        with mock.patch("app.main.connect", side_effect=psycopg.OperationalError("down")):
            response = self.client.get("/health")
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["status"], "DOWN")

    def test_delivery_backlog_is_degraded(self):
        app.state.settings = settings_with(health_backlog_max=1)
        finish_failed(insert_case())
        body = self.client.get("/health").json()
        self.assertEqual(body["status"], "DEGRADED")
        self.assertTrue(any("waiting for Phase 5" in r for r in body["reasons"]))

    def test_failed_spike_is_degraded(self):
        app.state.settings = settings_with(health_failed_spike=1)
        finish_failed(insert_case())
        body = self.client.get("/health").json()
        self.assertEqual(body["status"], "DEGRADED")
        self.assertTrue(any("FAILED" in r for r in body["reasons"]))

    def test_metrics(self):
        finish_failed(insert_case())
        response = self.client.get("/metrics")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        for key in ("cases_received", "cases_by_stage", "results", "failed_by_reason", "deliveries_pending",
                    "gemini_reasoning_calls", "average_passes_per_case", "worker_since_start"):
            self.assertIn(key, body)
        self.assertGreaterEqual(body["results"]["FAILED"], 1)
        self.assertGreaterEqual(body["failed_by_reason"]["LLM_REFUSED"], 1)

    def test_metrics_without_database_is_503(self):
        with mock.patch("app.main.connect", side_effect=psycopg.OperationalError("down")):
            self.assertEqual(self.client.get("/metrics").status_code, 503)


class StartupTest(unittest.TestCase):
    def tearDown(self):
        for handler in list(logging.getLogger().handlers):
            if handler.get_name() == "ai-service":
                logging.getLogger().removeHandler(handler)

    def test_startup_with_worker_off(self):
        with mock.patch.dict(os.environ, {"WORKER_ENABLED": "false"}):
            with TestClient(app) as client:
                self.assertIsNone(app.state.worker)
                self.assertEqual(client.get("/health").json()["worker"], "off")

    def test_invalid_settings_stop_startup(self):
        with mock.patch.dict(os.environ, {"WORKER_ENABLED": "false", "MAX_CONCURRENT_INVESTIGATIONS": "0"}):
            with self.assertRaises(ConfigError):
                with TestClient(app):
                    pass


if __name__ == "__main__":
    unittest.main()
