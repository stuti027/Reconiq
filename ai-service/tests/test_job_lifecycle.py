"""The case lifecycle in investigation.cases, against the sandbox database.

Test rows get a received_at far in the past, so claims (oldest first) take them before any other row in the
sandbox table, and a limit equal to the number of test rows never touches anyone else's cases.
"""

import unittest
import uuid
from datetime import datetime, timezone

from psycopg.types.json import Json

from app.contracts import InvestigationRequest
from app.jobs import (
    AWAITING_DELIVERY,
    DELIVERED,
    IN_PROGRESS,
    RECEIVED,
    case_stats,
    claim_cases,
    due_deliveries,
    ensure_job_schema,
    mark_delivered,
    mark_delivery_failed,
    recover_after_crash,
    release_case,
    store_result,
)
from app.main import connect
from app.result import EvidenceReference, InvestigationResult, failed_result

PREFIX = "job-test-"
OLD = datetime(2000, 1, 1, tzinfo=timezone.utc)
ROW = EvidenceReference(kind="transaction", ref_id="evt_gw_fee_001")


def delete_test_rows() -> None:
    with connect() as conn:
        conn.execute("DELETE FROM investigation.cases WHERE case_id LIKE %s", (PREFIX + "%",))


def insert_case(status: str = RECEIVED, pickups: int = 0, offset_seconds: int = 0) -> str:
    case_id = PREFIX + uuid.uuid4().hex
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO investigation.cases
                (case_id, source_phase, transaction_ids, context, content_hash, status, received_at, pickup_count)
            VALUES (%s, 'PHASE_3', %s, %s, 'test', %s, %s + make_interval(secs => %s), %s)
            """,
            (case_id, Json(["TXN-FEE-001"]), Json({}), status, OLD, offset_seconds, pickups),
        )
    return case_id


def row(case_id: str) -> dict:
    with connect() as conn:
        r = conn.execute(
            """
            SELECT status, pickup_count, result, failure_reason, delivery_attempts, last_error,
                   delivered_at, next_delivery_at > now() + interval '20 seconds'
            FROM investigation.cases WHERE case_id = %s
            """,
            (case_id,),
        ).fetchone()
    keys = ["status", "pickups", "result", "failure_reason", "attempts", "last_error", "delivered_at", "scheduled_later"]
    return dict(zip(keys, r))


def resolved(case_id: str) -> InvestigationResult:
    return InvestigationResult(
        case_id=case_id, source_phase="PHASE_3", transaction_ids=["TXN-FEE-001"], status="RESOLVED",
        root_cause="Gateway fee", cited_evidence=[ROW], examined_evidence=[ROW], failure_reason=None,
        attempts=1, model="test-model", investigated_at=datetime.now(timezone.utc),
    )


class LifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with connect() as conn:
            ensure_job_schema(conn)

    def setUp(self):
        delete_test_rows()

    def tearDown(self):
        delete_test_rows()

    def test_schema_setup_can_run_again(self):
        with connect() as conn:
            ensure_job_schema(conn)
            ensure_job_schema(conn)

    def test_claim_takes_oldest_received_and_counts_the_pickup(self):
        first, second, third = insert_case(offset_seconds=0), insert_case(offset_seconds=1), insert_case(offset_seconds=2)
        with connect() as conn:
            claimed = claim_cases(conn, 2)
        self.assertEqual([c.request.case_id for c in claimed], [first, second])
        self.assertEqual([c.pickup_count for c in claimed], [1, 1])
        self.assertEqual(claimed[0].request.transaction_ids, ["TXN-FEE-001"])
        self.assertEqual(row(first)["status"], IN_PROGRESS)
        self.assertEqual(row(third)["status"], RECEIVED)

    def test_two_claims_never_take_the_same_case(self):
        ids = {insert_case(offset_seconds=i) for i in range(4)}
        with connect() as a, connect() as b:
            first = claim_cases(a, 2)
            second = claim_cases(b, 2)
        taken = [c.request.case_id for c in first + second]
        self.assertEqual(len(taken), len(set(taken)))
        self.assertEqual(set(taken), ids)

    def test_release_puts_the_case_back_and_undoes_the_pickup(self):
        case_id = insert_case()
        with connect() as conn:
            claim_cases(conn, 1)
        with connect() as conn:
            self.assertTrue(release_case(conn, case_id))
        self.assertEqual((row(case_id)["status"], row(case_id)["pickups"]), (RECEIVED, 0))

    def test_store_result_moves_to_awaiting_delivery(self):
        case_id = insert_case()
        with connect() as conn:
            claim_cases(conn, 1)
            self.assertTrue(store_result(conn, resolved(case_id)))
        r = row(case_id)
        self.assertEqual(r["status"], AWAITING_DELIVERY)
        self.assertEqual(r["result"]["status"], "RESOLVED")
        self.assertIsNone(r["failure_reason"])

    def test_store_result_only_for_a_case_in_progress(self):
        case_id = insert_case()
        with connect() as conn:
            self.assertFalse(store_result(conn, resolved(case_id)))
        self.assertEqual(row(case_id)["status"], RECEIVED)

    def test_failed_result_keeps_its_reason(self):
        case_id = insert_case()
        request = InvestigationRequest(case_id=case_id, source_phase="PHASE_3", transaction_ids=["TXN-FEE-001"])
        with connect() as conn:
            claim_cases(conn, 1)
            store_result(conn, failed_result(request, failure_reason="LLM_REFUSED", model="m"))
        self.assertEqual(row(case_id)["failure_reason"], "LLM_REFUSED")

    def test_delivery_success(self):
        case_id = insert_case()
        with connect() as conn:
            claim_cases(conn, 1)
            store_result(conn, resolved(case_id))
        with connect() as conn:
            due = [d for d in due_deliveries(conn, 100) if d.case_id == case_id]
        self.assertEqual(len(due), 1)
        self.assertEqual(due[0].result, resolved(case_id).model_copy(update={"investigated_at": due[0].result.investigated_at}))
        with connect() as conn:
            self.assertTrue(mark_delivered(conn, case_id))
        r = row(case_id)
        self.assertEqual(r["status"], DELIVERED)
        self.assertIsNotNone(r["delivered_at"])

    def test_failed_delivery_waits_and_counts(self):
        case_id = insert_case()
        with connect() as conn:
            claim_cases(conn, 1)
            store_result(conn, resolved(case_id))
            mark_delivery_failed(conn, case_id, delay_seconds=60, error="503 from Phase 5")
        r = row(case_id)
        self.assertEqual((r["status"], r["attempts"], r["last_error"]), (AWAITING_DELIVERY, 1, "503 from Phase 5"))
        self.assertTrue(r["scheduled_later"])
        with connect() as conn:
            self.assertNotIn(case_id, [d.case_id for d in due_deliveries(conn, 1000)])

    def test_crash_recovery_resets_in_progress_cases(self):
        case_id = insert_case()
        with connect() as conn:
            claim_cases(conn, 1)
        with connect() as conn:
            report = recover_after_crash(conn, max_pickups=3, model="m")
        self.assertIn(case_id, report.reset)
        self.assertEqual((row(case_id)["status"], row(case_id)["pickups"]), (RECEIVED, 1))

    def test_third_crash_makes_a_poison_case(self):
        case_id = insert_case(pickups=2)
        with connect() as conn:
            claim_cases(conn, 1)
        with connect() as conn:
            report = recover_after_crash(conn, max_pickups=3, model="gemini-test")
        self.assertIn(case_id, report.poisoned)
        r = row(case_id)
        self.assertEqual((r["status"], r["failure_reason"]), (AWAITING_DELIVERY, "POISON_CASE"))
        self.assertEqual(r["result"]["status"], "FAILED")
        self.assertEqual(r["result"]["model"], "gemini-test")

    def test_stats(self):
        done = insert_case()
        waiting = insert_case(offset_seconds=1)
        request = InvestigationRequest(case_id=waiting, source_phase="PHASE_3", transaction_ids=["TXN-FEE-001"])
        with connect() as conn:
            claim_cases(conn, 2)
            store_result(conn, resolved(done))
            store_result(conn, failed_result(request, failure_reason="LLM_REFUSED", model="m"))
            mark_delivered(conn, done)
        with connect() as conn:
            stats = case_stats(conn, failed_window_seconds=600)
        self.assertGreaterEqual(stats.backlog, 1)
        self.assertGreaterEqual(stats.failed_recent, 1)
        self.assertGreaterEqual(stats.failed_by_reason.get("LLM_REFUSED", 0), 1)
        self.assertGreaterEqual(stats.results_by_status.get("RESOLVED", 0), 1)
        self.assertIsNotNone(stats.oldest_backlog_seconds)
        self.assertGreaterEqual(stats.reasoning_calls, 1)


if __name__ == "__main__":
    unittest.main()
