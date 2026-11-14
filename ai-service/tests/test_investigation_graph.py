"""Single-pass investigation graph. Uses the scripted reasoner and a fake embedder. No Gemini calls."""

import unittest
import uuid

import psycopg

from app.agent.evidence import EvidenceRef
from app.agent.fakes import ScriptedReasoner
from app.agent.graph import run_investigation
from app.agent.reasoning import ReasoningOutput
from app.contracts import InvestigationRequest
from app.main import connect
from app.tools.similar_cases import describe_case
from app.tools.transactions import get_transaction_group
from tests.test_similar_cases_tool import FakeEmbedder, stored_text, unit_vector

PREFIX = "step2-45-"
ROOT_CAUSE = "Gateway fee deducted before settlement"


def fee_rows() -> list[dict]:
    return get_transaction_group("transaction_id", "TXN-FEE-001") + get_transaction_group(
        "transaction_id", "BANK-FEE-001"
    )


def request_for(case_id: str, transaction_ids: list[str]) -> InvestigationRequest:
    return InvestigationRequest(
        case_id=case_id,
        source_phase="PHASE_6",
        transaction_ids=transaction_ids,
        context={},
    )


def answer(confident: bool, cited: list[str]) -> ReasoningOutput:
    return ReasoningOutput(
        confident=confident,
        root_cause=ROOT_CAUSE if confident else None,
        cited_evidence=cited,
        missing=[],
    )


class InvestigationGraphTest(unittest.TestCase):
    def setUp(self) -> None:
        self.created: list[str] = []

    def tearDown(self) -> None:
        try:
            with connect() as conn:
                conn.execute(
                    "DELETE FROM investigation.past_cases WHERE case_id = ANY(%s) OR case_id LIKE %s",
                    (self.created, PREFIX + "%"),
                )
        except psycopg.errors.UndefinedTable:
            return

    def new_case_id(self) -> str:
        case_id = PREFIX + uuid.uuid4().hex
        self.created.append(case_id)
        return case_id

    def embedder_for(self, description: str, *, will_save: bool) -> FakeEmbedder:
        vectors = {description: unit_vector(1, 0)}
        if will_save:
            vectors[stored_text(description, ROOT_CAUSE)] = unit_vector(1, 0)
        return FakeEmbedder(vectors)

    def saved_row(self, case_id: str):
        with connect() as conn:
            return conn.execute(
                """
                SELECT transaction_ids, root_cause
                FROM investigation.past_cases
                WHERE case_id = %s
                """,
                (case_id,),
            ).fetchone()

    def test_fee_request_gathers_both_rows_and_resolves_in_one_pass(self) -> None:
        case_id = self.new_case_id()
        description = describe_case(fee_rows())
        reasoner = ScriptedReasoner([answer(True, ["E1", "E2"])])
        outcome = run_investigation(
            request_for(case_id, ["TXN-FEE-001", "BANK-FEE-001"]),
            reasoner=reasoner,
            embedder=self.embedder_for(description, will_save=True),
        )

        txn_ids = [item.data["transaction_id"] for item in outcome.evidence if item.ref.kind == "transaction"]
        self.assertEqual(txn_ids, ["TXN-FEE-001", "BANK-FEE-001"])
        self.assertEqual(
            [item.fetch.value for item in outcome.evidence],
            ["TXN-FEE-001", "BANK-FEE-001"],
        )
        self.assertEqual(outcome.status, "RESOLVED")
        self.assertEqual(outcome.attempts, 1)
        self.assertEqual(len(reasoner.calls), 1)
        self.assertEqual(outcome.model, "scripted-test-reasoner")

    def test_confident_reasoner_saves_a_historical_case(self) -> None:
        case_id = self.new_case_id()
        description = describe_case(fee_rows())
        outcome = run_investigation(
            request_for(case_id, ["TXN-FEE-001", "BANK-FEE-001"]),
            reasoner=ScriptedReasoner([answer(True, ["E1", "E2"])]),
            embedder=self.embedder_for(description, will_save=True),
        )
        self.assertEqual(outcome.status, "RESOLVED")
        row = self.saved_row(case_id)
        self.assertIsNotNone(row)
        self.assertEqual(row[0], ["TXN-FEE-001", "BANK-FEE-001"])
        self.assertEqual(row[1], ROOT_CAUSE)

    def test_uncertain_reasoner_does_not_save(self) -> None:
        case_id = self.new_case_id()
        description = describe_case(fee_rows())
        embedder = self.embedder_for(description, will_save=False)
        outcome = run_investigation(
            request_for(case_id, ["TXN-FEE-001", "BANK-FEE-001"]),
            reasoner=ScriptedReasoner([answer(False, ["E1"])]),
            embedder=embedder,
        )
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertIsNone(outcome.root_cause)
        self.assertIsNone(self.saved_row(case_id))
        self.assertEqual(embedder.calls, [description])

    def test_missing_transactions_add_no_invented_evidence(self) -> None:
        case_id = self.new_case_id()
        missing = "TXN-DOES-NOT-EXIST-" + uuid.uuid4().hex
        description = describe_case([])
        outcome = run_investigation(
            request_for(case_id, [missing]),
            reasoner=ScriptedReasoner([answer(False, [])]),
            embedder=self.embedder_for(description, will_save=False),
        )
        self.assertEqual(outcome.evidence, [])
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertEqual(outcome.cited_references, [])
        self.assertIsNone(self.saved_row(case_id))

    def test_outcome_maps_labels_to_real_references(self) -> None:
        case_id = self.new_case_id()
        description = describe_case(fee_rows())
        reasoner = ScriptedReasoner([answer(True, ["E1", "E9", "E2"])])
        outcome = run_investigation(
            request_for(case_id, ["TXN-FEE-001", "BANK-FEE-001"]),
            reasoner=reasoner,
            embedder=self.embedder_for(description, will_save=True),
        )
        shown = reasoner.calls[0][1]
        self.assertEqual([item.label for item in shown], ["E1", "E2"])
        self.assertEqual(
            outcome.cited_references,
            [
                EvidenceRef("transaction", "evt_gw_fee_001"),
                EvidenceRef("transaction", "evt_bank_fee_001"),
            ],
        )
        self.assertTrue(all(isinstance(ref, EvidenceRef) for ref in outcome.cited_references))


if __name__ == "__main__":
    unittest.main()
