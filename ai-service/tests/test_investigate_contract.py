import unittest
import uuid

import psycopg
from fastapi.testclient import TestClient

from app.main import app, connect


def case_id() -> str:
    return "subphase42-" + uuid.uuid4().hex


class InvestigateContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.client = TestClient(app)
        self.created: list[str] = []

    def tearDown(self) -> None:
        if not self.created:
            return
        try:
            with connect() as conn:
                conn.execute(
                    """
                    DELETE FROM investigation.cases
                    WHERE case_id = ANY(%s)
                    """,
                    (self.created,),
                )
        except psycopg.errors.UndefinedTable:
            return

    def post(self, body: dict):
        response = self.client.post("/investigate", json=body)
        if response.status_code == 202 and isinstance(body.get("case_id"), str):
            self.created.append(body["case_id"])
        return response

    def test_phase3_request_is_accepted(self) -> None:
        response = self.post(
            {
                "case_id": case_id(),
                "source_phase": "PHASE_3",
                "transaction_ids": ["TXN-ONE"],
                "context": {"note": "single"},
            }
        )
        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.json()["status"], "accepted")

    def test_phase6_request_with_many_ids_is_accepted(self) -> None:
        cid = case_id()
        response = self.post(
            {
                "case_id": cid,
                "source_phase": "PHASE_6",
                "transaction_ids": ["TXN-A", "TXN-B", "TXN-C"],
                "context": {},
            }
        )
        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual(body["case_id"], cid)
        self.assertEqual(body["status"], "accepted")

    def test_malformed_request_is_422(self) -> None:
        response = self.client.post(
            "/investigate",
            content="not-json",
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(response.status_code, 422)

    def test_invalid_source_phase_is_422(self) -> None:
        response = self.post(
            {
                "case_id": case_id(),
                "source_phase": "PHASE_9",
                "transaction_ids": ["TXN-ONE"],
            }
        )
        self.assertEqual(response.status_code, 422)

    def test_empty_transaction_ids_is_422(self) -> None:
        response = self.post(
            {
                "case_id": case_id(),
                "source_phase": "PHASE_3",
                "transaction_ids": [],
            }
        )
        self.assertEqual(response.status_code, 422)

    def test_unknown_transaction_id_is_accepted(self) -> None:
        response = self.post(
            {
                "case_id": case_id(),
                "source_phase": "PHASE_3",
                "transaction_ids": ["TXN-DOES-NOT-EXIST"],
                "context": {},
            }
        )
        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.json()["status"], "accepted")

    def test_same_case_and_same_content_is_already_received(self) -> None:
        body = {
            "case_id": case_id(),
            "source_phase": "PHASE_3",
            "transaction_ids": ["TXN-ONE"],
            "context": {"fee": 50},
        }
        first = self.post(body)
        second = self.post(body)
        self.assertEqual(first.status_code, 202)
        self.assertEqual(first.json()["status"], "accepted")
        self.assertEqual(second.status_code, 202)
        self.assertEqual(second.json()["status"], "already_received")
        with connect() as conn:
            count = conn.execute(
                """
                SELECT count(*)
                FROM investigation.cases
                WHERE case_id = %s
                """,
                (body["case_id"],),
            ).fetchone()[0]
        self.assertEqual(count, 1)

    def test_same_case_and_different_content_is_conflict(self) -> None:
        cid = case_id()
        first = self.post(
            {
                "case_id": cid,
                "source_phase": "PHASE_3",
                "transaction_ids": ["TXN-ONE"],
            }
        )
        second = self.post(
            {
                "case_id": cid,
                "source_phase": "PHASE_3",
                "transaction_ids": ["TXN-OTHER"],
            }
        )
        self.assertEqual(first.status_code, 202)
        self.assertEqual(second.status_code, 409)
        self.assertEqual(second.json()["detail"], "CASE_ID_CONFLICT")

    def test_request_is_persisted_before_accepted_response(self) -> None:
        cid = case_id()
        response = self.post(
            {
                "case_id": cid,
                "source_phase": "PHASE_6",
                "transaction_ids": ["TXN-A", "TXN-B"],
                "context": {"cluster": "c1"},
            }
        )
        self.assertEqual(response.status_code, 202)
        with connect() as conn:
            row = conn.execute(
                """
                SELECT source_phase, transaction_ids, context, status
                FROM investigation.cases
                WHERE case_id = %s
                """,
                (cid,),
            ).fetchone()
        self.assertIsNotNone(row)
        self.assertEqual(row[0], "PHASE_6")
        self.assertEqual(row[1], ["TXN-A", "TXN-B"])
        self.assertEqual(row[2], {"cluster": "c1"})
        self.assertEqual(row[3], "accepted")


if __name__ == "__main__":
    unittest.main()
