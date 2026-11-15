"""The two sample mismatches through the whole graph with the real Gemini reasoner and embedder.

Off by default (RUN_GEMINI_TESTS=1 turns it on) because every run spends API quota. The checks look at the
structure of the outcome (status, number of passes, which rows are cited, which fetch was asked for), never at
the wording of the root cause. Cases written to past_cases are removed afterwards, so FEE never becomes a
"similar case" for a later run.
"""

import os
import sys
import time
import unittest
import uuid
from pathlib import Path
from typing import Sequence

from dotenv import load_dotenv

from app.agent.evidence import EvidenceRef, LabelledEvidence
from app.agent.graph import run_investigation
from app.agent.reasoning import FetchRequest, GeminiReasoner, ReasoningOutput
from app.contracts import InvestigationRequest
from app.main import connect
from app.tools.similar_cases import GeminiEmbedder

PREFIX = "real-gemini-test-"

FEE_GATEWAY = EvidenceRef(kind="transaction", ref_id="evt_gw_fee_001")
FEE_BANK = EvidenceRef(kind="transaction", ref_id="evt_bank_fee_001")
SPLIT_GATEWAY = EvidenceRef(kind="transaction", ref_id="evt_gw_split_001")
SPLIT_BANK_A = EvidenceRef(kind="transaction", ref_id="evt_bank_split_001a")
SPLIT_BANK_B = EvidenceRef(kind="transaction", ref_id="evt_bank_split_001b")
BY_SETTLEMENT = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")


class RecordingReasoner:
    """Passes each call to the real reasoner and keeps every answer, so pass 1 can be checked on its own."""

    def __init__(self, inner: GeminiReasoner) -> None:
        self._inner = inner
        self.model = inner.model
        self.outputs: list[ReasoningOutput] = []

    def reason(self, request: InvestigationRequest, evidence: Sequence[LabelledEvidence]) -> ReasoningOutput:
        output = self._inner.reason(request, evidence)
        self.outputs.append(output)
        return output


def delete_test_cases() -> None:
    with connect() as conn:
        conn.execute("DELETE FROM investigation.past_cases WHERE case_id LIKE %s", (PREFIX + "%",))


@unittest.skipUnless(os.environ.get("RUN_GEMINI_TESTS") == "1", "set RUN_GEMINI_TESTS=1 to call the real Gemini API")
class RealGeminiCasesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        load_dotenv(Path(__file__).resolve().parents[1] / ".env")
        cls.embedder = GeminiEmbedder()
        delete_test_cases()

    def tearDown(self) -> None:
        delete_test_cases()

    def investigate(self, transaction_ids: list[str]):
        self.reasoner = RecordingReasoner(GeminiReasoner())
        request = InvestigationRequest(
            case_id=PREFIX + uuid.uuid4().hex, source_phase="PHASE_3", transaction_ids=transaction_ids
        )
        started = time.perf_counter()
        outcome = run_investigation(request, reasoner=self.reasoner, embedder=self.embedder)
        self.report(transaction_ids, outcome, time.perf_counter() - started)
        return outcome

    def report(self, transaction_ids: list[str], outcome, seconds: float) -> None:
        asked = [f"{f.tool}({f.key}={f.value})" for output in self.reasoner.outputs for f in output.missing]
        cited = sorted(ref.ref_id for ref in outcome.cited_references)
        print(
            f"\n[real-gemini] ids={transaction_ids} status={outcome.status} passes={outcome.attempts} "
            f"cited={cited} asked={asked} model={outcome.model} seconds={seconds:.1f}",
            file=sys.stderr,
        )

    def test_fee_with_both_ids_resolves_in_one_pass(self) -> None:
        outcome = self.investigate(["TXN-FEE-001", "BANK-FEE-001"])

        self.assertEqual(outcome.status, "RESOLVED")
        self.assertEqual(outcome.attempts, 1)
        self.assertLessEqual({FEE_GATEWAY, FEE_BANK}, set(outcome.cited_references))

    def test_split_with_gateway_id_asks_for_the_settlement_and_resolves_in_two(self) -> None:
        outcome = self.investigate(["TXN-SPLIT-001"])

        first = self.reasoner.outputs[0]
        self.assertFalse(first.confident, "one gateway row alone should not be enough")
        self.assertIn(BY_SETTLEMENT, first.missing)
        self.assertEqual(outcome.status, "RESOLVED")
        self.assertEqual(outcome.attempts, 2)
        self.assertLessEqual({SPLIT_GATEWAY, SPLIT_BANK_A, SPLIT_BANK_B}, set(outcome.cited_references))


if __name__ == "__main__":
    unittest.main()
