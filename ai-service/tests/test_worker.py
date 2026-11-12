"""The background worker: investigating claimed cases, sorting failures, delivering results, and the whole path
from POST /investigate to a result arriving at a Phase 5 stub.
"""

import threading
import time
import unittest
import uuid

import httpx
import psycopg
from fastapi.testclient import TestClient

from app.agent.evidence import EvidenceRef
from app.agent.fakes import ScriptedReasoner
from app.agent.graph import run_investigation
from app.agent.reasoning import LLMOutputInvalidError, LLMRefusedError, ReasoningOutput
from app.agent.state import InvestigationOutcome
from app.delivery import DeliveryConfigError, deliver_result
from app.jobs import AWAITING_DELIVERY, DELIVERED, RECEIVED, ensure_job_schema
from app.main import app, connect
from app.worker import Worker
from tests.test_agent_loop import AnyTextEmbedder
from tests.test_backoff_and_failures import gemini_error
from tests.test_job_lifecycle import PREFIX, delete_test_rows, insert_case, row
from tests.test_result_delivery import StubPhase5


def outcome(status: str = "RESOLVED") -> InvestigationOutcome:
    cited = [EvidenceRef("transaction", "evt_gw_fee_001")] if status == "RESOLVED" else []
    from tests.test_investigation_result import item

    return InvestigationOutcome(
        status=status,
        root_cause="Gateway fee" if status == "RESOLVED" else None,
        cited_references=cited,
        attempts=1,
        evidence=[item("evt_gw_fee_001")],
        model="scripted",
    )


def raising(exc: BaseException):
    def investigate(request):
        raise exc

    return investigate


class Recorder:
    def __init__(self, fail_with: BaseException | None = None) -> None:
        self.sent = []
        self.fail_with = fail_with

    def __call__(self, result) -> None:
        if self.fail_with is not None:
            raise self.fail_with
        self.sent.append(result)


def worker(investigate=lambda request: outcome(), deliver=None, **kwargs) -> Worker:
    options = dict(model="test-model", max_concurrent=2, poll_seconds=0.05, backoff_base=0.01, backoff_cap=0.05)
    options.update(kwargs)
    return Worker(connect=connect, investigate=investigate, deliver=deliver or Recorder(), **options)


def make_due(case_id: str) -> None:
    with connect() as conn:
        conn.execute("UPDATE investigation.cases SET next_delivery_at = now() WHERE case_id = %s", (case_id,))


class WorkerTestBase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with connect() as conn:
            ensure_job_schema(conn)

    def setUp(self):
        delete_test_rows()

    def tearDown(self):
        delete_test_rows()


class InvestigateRoundTest(WorkerTestBase):
    def test_finished_case_is_stored_for_delivery(self):
        case_id = insert_case()
        report = worker().investigate_once()
        self.assertEqual((report.claimed, report.environment_failure), (1, None))
        r = row(case_id)
        self.assertEqual(r["status"], AWAITING_DELIVERY)
        self.assertEqual((r["result"]["status"], r["result"]["case_id"]), ("RESOLVED", case_id))

    def test_low_confidence_is_a_normal_outcome(self):
        case_id = insert_case()
        worker(investigate=lambda request: outcome("LOW_CONFIDENCE")).investigate_once()
        self.assertEqual(row(case_id)["result"]["status"], "LOW_CONFIDENCE")
        self.assertIsNone(row(case_id)["failure_reason"])

    def test_case_failures_become_failed_results(self):
        for exc, code in [
            (LLMRefusedError("blocked"), "LLM_REFUSED"),
            (LLMOutputInvalidError("twice"), "LLM_OUTPUT_INVALID"),
            (KeyError("bug"), "PROCESSING_ERROR"),
            (gemini_error(400), "PROCESSING_ERROR"),
        ]:
            with self.subTest(code=code, exc=type(exc).__name__):
                case_id = insert_case()
                w = worker(investigate=raising(exc))
                w.investigate_once()
                r = row(case_id)
                self.assertEqual((r["status"], r["failure_reason"]), (AWAITING_DELIVERY, code))
                self.assertEqual((r["result"]["status"], r["result"]["model"]), ("FAILED", "test-model"))
                self.assertEqual(w.counters.snapshot()["case_failures"], 1)
                delete_test_rows()

    def test_environment_failures_put_the_case_back_untouched(self):
        for exc in (psycopg.OperationalError("db down"), psycopg.errors.UndefinedTable("no canonical table yet"),
                    gemini_error(503), gemini_error(429), httpx.ConnectError("x")):
            with self.subTest(exc=type(exc).__name__):
                case_id = insert_case()
                report = worker(investigate=raising(exc)).investigate_once()
                self.assertIsNotNone(report.environment_failure)
                r = row(case_id)
                self.assertEqual((r["status"], r["pickups"], r["result"]), (RECEIVED, 0, None))
                delete_test_rows()

    def test_retry_after_from_gemini_reaches_the_round(self):
        insert_case()
        report = worker(investigate=raising(gemini_error(429, {"Retry-After": "7"}))).investigate_once()
        self.assertEqual(report.environment_failure.retry_after, 7.0)

    def test_nothing_to_do(self):
        self.assertEqual(worker().investigate_once().claimed, 0)

    def test_at_most_max_concurrent_at_once(self):
        for i in range(3):
            insert_case(offset_seconds=i)
        running, peak, lock = [0], [0], threading.Lock()

        def slow(request):
            with lock:
                running[0] += 1
                peak[0] = max(peak[0], running[0])
            time.sleep(0.2)
            with lock:
                running[0] -= 1
            return outcome()

        w = worker(investigate=slow, max_concurrent=2)
        self.assertEqual(w.investigate_once().claimed, 2)
        self.assertEqual(peak[0], 2)
        self.assertEqual(w.investigate_once().claimed, 1)

    def test_database_down_when_storing_puts_the_case_back_later(self):
        case_id = insert_case()
        calls = [0]

        def flaky_connect():
            calls[0] += 1
            if calls[0] in (2, 3):  # store_result and the release right after it
                raise psycopg.OperationalError("db blip")
            return connect()

        w = Worker(connect=flaky_connect, investigate=lambda r: outcome(), deliver=Recorder(), model="m")
        self.assertIsNotNone(w.investigate_once().environment_failure)
        self.assertEqual(row(case_id)["status"], "IN_PROGRESS")
        w.investigate_once()
        self.assertEqual(row(case_id)["status"], AWAITING_DELIVERY)


class DeliverRoundTest(WorkerTestBase):
    def test_delivered_on_success(self):
        case_id = insert_case()
        recorder = Recorder()
        w = worker(deliver=recorder)
        w.investigate_once()
        self.assertGreaterEqual(w.deliver_once(), 1)
        self.assertIn(case_id, [r.case_id for r in recorder.sent])
        self.assertEqual(row(case_id)["status"], DELIVERED)

    def test_failed_delivery_waits_then_succeeds(self):
        case_id = insert_case()
        failing = Recorder(httpx.HTTPStatusError("503", request=httpx.Request("POST", "http://p5"),
                                                 response=httpx.Response(503)))
        worker(deliver=failing).investigate_once()
        worker(deliver=failing).deliver_once()
        r = row(case_id)
        self.assertEqual((r["status"], r["attempts"]), (AWAITING_DELIVERY, 1))
        self.assertIn("503", r["last_error"])

        make_due(case_id)
        recorder = Recorder()
        worker(deliver=recorder).deliver_once()
        self.assertEqual(row(case_id)["status"], DELIVERED)

    def test_missing_phase5_url_keeps_results_waiting(self):
        case_id = insert_case()
        w = worker(deliver=Recorder(DeliveryConfigError("PHASE5_RESULT_URL is not set")))
        w.investigate_once()
        w.deliver_once()
        self.assertEqual(row(case_id)["status"], AWAITING_DELIVERY)
        self.assertEqual(w.counters.snapshot()["delivery_failures"], 1)


class RecoveryTest(WorkerTestBase):
    def test_startup_recovery(self):
        resumed = insert_case(status="IN_PROGRESS", pickups=1)
        poisoned = insert_case(status="IN_PROGRESS", pickups=3, offset_seconds=1)
        report = worker(model="gemini-test").recover()
        self.assertIn(resumed, report.reset)
        self.assertIn(poisoned, report.poisoned)
        self.assertEqual(row(resumed)["status"], RECEIVED)
        self.assertEqual(row(poisoned)["failure_reason"], "POISON_CASE")


class EndToEndTest(WorkerTestBase):
    """POST /investigate -> stored -> worker loop -> agent (scripted reasoner, real rows) -> Phase 5 stub."""

    def test_request_reaches_phase5(self):
        case_id = PREFIX + uuid.uuid4().hex
        reasoner = ScriptedReasoner([
            ReasoningOutput(confident=True, root_cause="Gateway fee deducted", cited_evidence=["E1", "E2"], missing=[])
        ])

        def investigate(request):
            return run_investigation(request, reasoner=reasoner, embedder=AnyTextEmbedder())

        response = TestClient(app).post(
            "/investigate",
            json={"case_id": case_id, "source_phase": "PHASE_3", "transaction_ids": ["TXN-FEE-001", "BANK-FEE-001"]},
        )
        self.assertEqual(response.status_code, 202)
        self.assertEqual(row(case_id)["status"], RECEIVED)

        with StubPhase5() as stub:
            w = worker(investigate=investigate, deliver=lambda result: deliver_result(result, url=stub.url))
            w.start()
            try:
                deadline = time.time() + 15
                while row(case_id)["status"] != DELIVERED and time.time() < deadline:
                    time.sleep(0.05)
            finally:
                w.stop()
            bodies = [p["body"] for p in stub.received if p["body"]["case_id"] == case_id]

        self.assertEqual(row(case_id)["status"], DELIVERED)
        self.assertEqual(len(bodies), 1)
        self.assertEqual(bodies[0]["status"], "RESOLVED")
        self.assertEqual(sorted(r["ref_id"] for r in bodies[0]["cited_evidence"]), ["evt_bank_fee_001", "evt_gw_fee_001"])
        with connect() as conn:
            conn.execute("DELETE FROM investigation.past_cases WHERE case_id = %s", (case_id,))


if __name__ == "__main__":
    unittest.main()
