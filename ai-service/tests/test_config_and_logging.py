"""Startup configuration checks and the logging rules (case id on every line, secrets masked)."""

import io
import logging
import threading
import unittest

from app.config import ConfigError, defaults, load_settings
from app.logging_setup import case_context, configure_logging

VALID = {
    "POSTGRES_HOST": "127.0.0.1",
    "POSTGRES_PORT": "5432",
    "POSTGRES_DB": "reconciliation",
    "POSTGRES_USER": "reconiq",
    "POSTGRES_PASSWORD": "pw-secret",
    "GEMINI_API_KEY": "key-secret-123",
    "GEMINI_MODEL": "gemini-test",
    "GEMINI_EMBEDDING_MODEL": "embed-test",
}


class SettingsTest(unittest.TestCase):
    def test_valid_environment(self):
        settings = load_settings(VALID)
        self.assertEqual(settings.postgres_port, 5432)
        self.assertEqual(settings.max_concurrent_investigations, 2)
        self.assertIsNone(settings.phase5_result_url)
        self.assertTrue(settings.worker_enabled)

    def test_every_problem_is_reported_at_once(self):
        env = dict(VALID, POSTGRES_PORT="not-a-port", MAX_CONCURRENT_INVESTIGATIONS="0")
        del env["GEMINI_API_KEY"]
        del env["POSTGRES_HOST"]
        with self.assertRaises(ConfigError) as ctx:
            load_settings(env)
        named = {p.split(":")[0] for p in ctx.exception.problems}
        self.assertEqual(named, {"POSTGRES_HOST", "POSTGRES_PORT", "GEMINI_API_KEY", "MAX_CONCURRENT_INVESTIGATIONS"})

    def test_empty_values_count_as_unset(self):
        settings = load_settings(dict(VALID, PHASE5_RESULT_URL="  ", LOG_LEVEL=""))
        self.assertIsNone(settings.phase5_result_url)
        self.assertEqual(settings.log_level, "INFO")
        with self.assertRaises(ConfigError):
            load_settings(dict(VALID, GEMINI_MODEL=""))

    def test_secrets_never_appear_in_repr(self):
        text = repr(load_settings(VALID))
        self.assertNotIn("key-secret-123", text)
        self.assertNotIn("pw-secret", text)

    def test_backoff_cap_not_below_base(self):
        with self.assertRaises(ConfigError):
            load_settings(dict(VALID, BACKOFF_BASE_SECONDS="10", BACKOFF_CAP_SECONDS="5"))

    def test_optional_settings_are_read(self):
        settings = load_settings(dict(VALID, WORKER_ENABLED="false", PHASE5_RESULT_URL="http://phase5/results"))
        self.assertFalse(settings.worker_enabled)
        self.assertEqual(settings.phase5_result_url, "http://phase5/results")

    def test_defaults_without_environment(self):
        self.assertEqual(defaults().health_backlog_max, 50)


class LoggingTest(unittest.TestCase):
    def setUp(self):
        self.stream = io.StringIO()
        self.handler = configure_logging("DEBUG", secrets=["key-secret-123"], stream=self.stream)
        self.log = logging.getLogger("test.logging")

    def tearDown(self):
        logging.getLogger().removeHandler(self.handler)

    def test_case_id_on_every_line(self):
        self.log.info("outside")
        with case_context("case-42"):
            self.log.info("inside")
        lines = self.stream.getvalue().splitlines()
        self.assertIn("case=- ", lines[0])
        self.assertIn("case=case-42 ", lines[1])

    def test_case_id_is_per_thread(self):
        def other():
            self.log.info("other thread")

        with case_context("case-main"):
            t = threading.Thread(target=other)
            t.start()
            t.join()
        self.assertIn("case=- ", self.stream.getvalue())

    def test_secret_is_masked(self):
        self.log.warning("calling with key %s", "key-secret-123")
        output = self.stream.getvalue()
        self.assertNotIn("key-secret-123", output)
        self.assertIn("***", output)

    def test_configuring_twice_keeps_one_handler(self):
        handler = configure_logging("INFO", stream=self.stream)
        ours = [h for h in logging.getLogger().handlers if h.get_name() == "ai-service"]
        self.assertEqual(ours, [handler])
        self.handler = handler


if __name__ == "__main__":
    unittest.main()
