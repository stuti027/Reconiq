"""Delivery of a finished result to Phase 5, against a local stub receiver standing in for Phase 5."""

import json
import os
import socket
import threading
import unittest
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

import httpx

from app.delivery import RESULT_URL_ENV, DeliveryConfigError, deliver_result
from app.result import EvidenceReference, InvestigationResult

ROW = EvidenceReference(kind="transaction", ref_id="evt_gw_fee_001")


def sample_result() -> InvestigationResult:
    return InvestigationResult(
        case_id="delivery-1",
        source_phase="PHASE_3",
        transaction_ids=["TXN-FEE-001"],
        status="RESOLVED",
        root_cause="Gateway fee deducted before settlement",
        cited_evidence=[ROW],
        examined_evidence=[ROW],
        failure_reason=None,
        attempts=1,
        model="gemini-test",
        investigated_at=datetime(2026, 11, 11, 9, 0, tzinfo=timezone.utc),
    )


class StubPhase5:
    """A tiny HTTP server that records every POST and answers with a chosen status code."""

    def __init__(self, status: int = 202) -> None:
        self.status = status
        self.received: list[dict] = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                body = self.rfile.read(int(self.headers["Content-Length"]))
                stub.received.append(
                    {"path": self.path, "content_type": self.headers["Content-Type"], "body": json.loads(body)}
                )
                self.send_response(stub.status)
                self.end_headers()

            def log_message(self, *args) -> None:
                pass

        self._server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}/investigation-results"
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    def __enter__(self) -> "StubPhase5":
        self._thread.start()
        return self

    def __exit__(self, *exc) -> None:
        self._server.shutdown()
        self._server.server_close()


def unused_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class DeliveryTest(unittest.TestCase):
    def test_result_is_posted_as_json(self):
        result = sample_result()
        with StubPhase5() as stub:
            deliver_result(result, url=stub.url)
        self.assertEqual(len(stub.received), 1)
        sent = stub.received[0]
        self.assertEqual(sent["path"], "/investigation-results")
        self.assertEqual(sent["content_type"], "application/json")
        self.assertEqual(sent["body"], result.model_dump(mode="json"))
        self.assertEqual(InvestigationResult.model_validate(sent["body"]), result)

    def test_url_comes_from_the_environment(self):
        with StubPhase5() as stub, mock.patch.dict(os.environ, {RESULT_URL_ENV: stub.url}):
            deliver_result(sample_result())
        self.assertEqual(len(stub.received), 1)

    def test_missing_url_is_a_config_error(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(RESULT_URL_ENV, None)
            with self.assertRaises(DeliveryConfigError):
                deliver_result(sample_result())

    def test_error_reply_raises(self):
        for status in (400, 500, 503):
            with self.subTest(status=status), StubPhase5(status=status) as stub:
                with self.assertRaises(httpx.HTTPStatusError) as ctx:
                    deliver_result(sample_result(), url=stub.url)
                self.assertEqual(ctx.exception.response.status_code, status)
                self.assertEqual(len(stub.received), 1, "one attempt, no retry here")

    def test_unreachable_receiver_raises_the_network_error(self):
        with self.assertRaises(httpx.ConnectError):
            deliver_result(sample_result(), url=f"http://127.0.0.1:{unused_port()}/investigation-results")

    def test_given_client_is_used(self):
        seen = []
        transport = httpx.MockTransport(lambda req: seen.append(req) or httpx.Response(202))
        with httpx.Client(transport=transport) as client:
            deliver_result(sample_result(), url="http://phase5.test/results", client=client)
        self.assertEqual(len(seen), 1)
        self.assertEqual(json.loads(seen[0].content)["case_id"], "delivery-1")


if __name__ == "__main__":
    unittest.main()
