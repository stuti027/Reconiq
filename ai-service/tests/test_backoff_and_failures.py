"""The retry delay helper and the failure classifier."""

import unittest
from datetime import datetime, timedelta, timezone

import httpx
import psycopg
from google.genai import errors as genai_errors

from app.agent.reasoning import LLMOutputInvalidError, LLMRefusedError
from app.backoff import backoff_delay, retry_after_seconds
from app.failures import PROCESSING_ERROR, FailureClass, classify


def gemini_error(code: int, headers: dict | None = None) -> genai_errors.APIError:
    response = httpx.Response(code, headers=headers or {}, request=httpx.Request("POST", "https://gemini.test"))
    cls = genai_errors.ServerError if code >= 500 else genai_errors.ClientError
    return cls(code, {"error": {"code": code, "message": "test", "status": "TEST"}}, response)


class BackoffDelayTest(unittest.TestCase):
    def test_grows_exponentially_between_half_and_full(self):
        for attempt, ceiling in [(0, 1.0), (1, 2.0), (2, 4.0), (3, 8.0)]:
            with self.subTest(attempt=attempt):
                self.assertEqual(backoff_delay(attempt, rng=lambda: 0.0), ceiling / 2)
                self.assertEqual(backoff_delay(attempt, rng=lambda: 1.0), ceiling)

    def test_is_capped(self):
        self.assertEqual(backoff_delay(20, cap=60.0, rng=lambda: 1.0), 60.0)
        self.assertEqual(backoff_delay(10_000, cap=5.0, rng=lambda: 1.0), 5.0)

    def test_retry_after_is_a_floor(self):
        self.assertEqual(backoff_delay(0, retry_after=30.0, rng=lambda: 1.0), 30.0)
        self.assertEqual(backoff_delay(5, retry_after=1.0, rng=lambda: 1.0), 32.0)

    def test_negative_attempt_is_refused(self):
        with self.assertRaises(ValueError):
            backoff_delay(-1)


class RetryAfterTest(unittest.TestCase):
    def test_seconds(self):
        self.assertEqual(retry_after_seconds({"Retry-After": "30"}), 30.0)

    def test_http_date(self):
        now = datetime(2026, 11, 12, 10, 0, 0, tzinfo=timezone.utc)
        header = (now + timedelta(seconds=90)).strftime("%a, %d %b %Y %H:%M:%S GMT")
        self.assertAlmostEqual(retry_after_seconds({"Retry-After": header}, now=now), 90.0)

    def test_missing_or_unreadable(self):
        for headers in (None, {}, {"Retry-After": "soon"}):
            with self.subTest(headers=headers):
                self.assertIsNone(retry_after_seconds(headers))


class ClassifyTest(unittest.TestCase):
    def test_reasoner_errors_are_case_failures_with_their_code(self):
        self.assertEqual(classify(LLMOutputInvalidError("x")).reason_code, "LLM_OUTPUT_INVALID")
        self.assertEqual(classify(LLMRefusedError("x")).failure_class, FailureClass.CASE)
        self.assertEqual(classify(LLMRefusedError("x")).reason_code, "LLM_REFUSED")

    def test_database_outages_are_environment(self):
        for exc in (psycopg.OperationalError("down"), psycopg.InterfaceError("closed")):
            with self.subTest(exc=type(exc).__name__):
                self.assertEqual(classify(exc).failure_class, FailureClass.ENVIRONMENT)

    def test_gemini_busy_limited_or_misconfigured_is_environment(self):
        for code in (429, 500, 503, 401, 403, 404):
            with self.subTest(code=code):
                failure = classify(gemini_error(code))
                self.assertEqual(failure.failure_class, FailureClass.ENVIRONMENT)
                self.assertIsNone(failure.reason_code)

    def test_gemini_retry_after_is_kept(self):
        self.assertEqual(classify(gemini_error(429, {"Retry-After": "12"})).retry_after, 12.0)

    def test_invalid_gemini_key_is_environment(self):
        response = httpx.Response(400, request=httpx.Request("POST", "https://gemini.test"))
        body = {"error": {"code": 400, "message": "API key not valid.", "status": "INVALID_ARGUMENT",
                          "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID"}]}}
        failure = classify(genai_errors.ClientError(400, body, response))
        self.assertEqual((failure.failure_class, failure.reason_code), (FailureClass.ENVIRONMENT, None))

    def test_gemini_bad_request_is_a_case_failure(self):
        failure = classify(gemini_error(400))
        self.assertEqual((failure.failure_class, failure.reason_code), (FailureClass.CASE, PROCESSING_ERROR))

    def test_network_errors_are_environment(self):
        for exc in (httpx.ConnectError("refused"), httpx.ReadTimeout("slow"), TimeoutError(), ConnectionError()):
            with self.subTest(exc=type(exc).__name__):
                self.assertEqual(classify(exc).failure_class, FailureClass.ENVIRONMENT)

    def test_anything_else_is_a_processing_error(self):
        for exc in (KeyError("x"), ValueError("bad"), psycopg.errors.UndefinedTable("no table")):
            with self.subTest(exc=type(exc).__name__):
                failure = classify(exc)
                self.assertEqual((failure.failure_class, failure.reason_code), (FailureClass.CASE, PROCESSING_ERROR))


if __name__ == "__main__":
    unittest.main()
