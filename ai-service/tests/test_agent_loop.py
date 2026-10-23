"""Every path through the investigation graph, driven by the scripted reasoner.

Real lookups against the sandbox's canonical rows; a fake embedder (any text -> one fixed vector, unique model
name) so no Gemini calls are made and other rows in past_cases are never seen.
"""

import unittest
import uuid

import psycopg

from app.agent.evidence import EvidenceRef
from app.agent.fakes import ScriptedReasoner
from app.agent.graph import run_investigation
from app.agent.reasoning import FetchRequest, LLMOutputInvalidError, LLMRefusedError, ReasoningOutput
from app.contracts import InvestigationRequest
from app.main import connect
from app.tools.transactions import get_transaction_group as real_get_transaction_group
from tests.test_similar_cases_tool import unit_vector

PREFIX = "agent-loop-test-"
BY_SETTLEMENT = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")


class AnyTextEmbedder:
    """Same vector for every text; records what it was asked to embed."""

    def __init__(self) -> None:
        self.model = "test-fake-" + uuid.uuid4().hex
        self.calls: list[str] = []

    def embed(self, text: str) -> list[float]:
        self.calls.append(text)
        return unit_vector(1, 0)


def unsure(cited: list[str], missing: list[FetchRequest] = ()) -> ReasoningOutput:
    return ReasoningOutput(confident=False, root_cause=None, cited_evidence=cited, missing=list(missing))


def sure(cited: list[str], cause: str = "Split settlement paid in two bank transfers") -> ReasoningOutput:
    return ReasoningOutput(confident=True, root_cause=cause, cited_evidence=cited, missing=[])


class LoopTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.embedder = AnyTextEmbedder()

    def tearDown(self) -> None:
        try:
            with connect() as conn:
                conn.execute("DELETE FROM investigation.past_cases WHERE case_id LIKE %s", (PREFIX + "%",))
        except psycopg.errors.UndefinedTable:
            pass

    def run_case(self, transaction_ids: list[str], script: list, **tools):
        case_id = PREFIX + uuid.uuid4().hex
        self.reasoner = ScriptedReasoner(script)
        request = InvestigationRequest(case_id=case_id, source_phase="PHASE_3", transaction_ids=transaction_ids)
        outcome = run_investigation(request, reasoner=self.reasoner, embedder=self.embedder, **tools)
        return case_id, outcome

    def saved(self, case_id: str):
        with connect() as conn:
            return conn.execute(
                "SELECT description_text, root_cause FROM investigation.past_cases WHERE case_id = %s", (case_id,)
            ).fetchone()


class GraphPathTest(LoopTestBase):
    """The six scripted paths."""

    def test_1_confident_on_first_pass_finishes_in_one(self):
        case_id, outcome = self.run_case(["TXN-FEE-001", "BANK-FEE-001"], [sure(["E1", "E2"], "Gateway fee deducted")])
        self.assertEqual(outcome.status, "RESOLVED")
        self.assertEqual(outcome.attempts, 1)
        self.assertEqual(len(self.reasoner.calls), 1)
        self.assertEqual(len(self.embedder.calls), 2, "one search, one write-back")
        self.assertIsNotNone(self.saved(case_id))

    def test_2_unsure_with_new_fetch_retries_exactly_once_with_that_fetch(self):
        _, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [BY_SETTLEMENT]), sure(["E1", "E2", "E3"])])
        self.assertEqual(outcome.status, "RESOLVED")
        self.assertEqual(outcome.attempts, 2)
        self.assertEqual(len(self.reasoner.calls), 2)
        pass_two = [item for item in outcome.evidence if item.fetch == BY_SETTLEMENT]
        self.assertEqual(len(pass_two), 3, "the settlement lookup returns the gateway row and both bank rows")
        fetches_used = {item.fetch for item in outcome.evidence}
        self.assertEqual(
            {(f.tool, f.key) for f in fetches_used},
            {("get_transaction_group", "transaction_id"), ("get_transaction_group", "settlement_id")},
        )

    def test_3_still_unsure_after_retry_is_low_confidence_and_stops(self):
        third = sure(["E1"])
        case_id, outcome = self.run_case(
            ["TXN-SPLIT-001"],
            [
                unsure(["E1"], [BY_SETTLEMENT]),
                unsure(["E1", "E2"], [FetchRequest(tool="get_transaction_group", key="reference_key", value="REFX")]),
                third,
            ],
        )
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertEqual(outcome.attempts, 2)
        self.assertEqual(len(self.reasoner.calls), 2, "no third pass even though a new fetch was asked for")
        self.assertIsNone(self.saved(case_id))

    def test_4_asking_only_for_what_was_already_fetched_does_not_retry(self):
        repeat = FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN-FEE-001")
        _, outcome = self.run_case(["TXN-FEE-001"], [unsure(["E1"], [repeat]), sure(["E1"])])
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertEqual(outcome.attempts, 1)
        self.assertEqual(len(self.reasoner.calls), 1)

    def test_5_confident_without_citations_is_overridden(self):
        case_id, outcome = self.run_case(["TXN-FEE-001", "BANK-FEE-001"], [sure([])])
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertIsNone(self.saved(case_id), "an overridden answer is never remembered")
        self.assertEqual(len(self.embedder.calls), 1, "search only, no write-back embedding")

    def test_5b_confident_citing_unseen_label_is_overridden(self):
        _, outcome = self.run_case(["TXN-FEE-001", "BANK-FEE-001"], [sure(["E1", "E7"])])
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertEqual(outcome.cited_references[0].kind, "transaction")
        self.assertEqual(len(outcome.cited_references), 1, "only the known label maps to a real id")

    def test_6_invalid_output_is_raised_and_no_retry_is_spent(self):
        with self.assertRaises(LLMOutputInvalidError):
            self.run_case(["TXN-SPLIT-001"], [LLMOutputInvalidError("failed validation twice"), sure(["E1"])])
        self.assertEqual(len(self.reasoner.calls), 1)
        self.assertEqual(len(self.embedder.calls), 1, "pass 1 ran once; no second gather")


class SplitCaseTest(LoopTestBase):
    """The anchor case: only the gateway id is known, the bank side is found by settlement."""

    def test_reasoner_sees_more_on_the_second_look(self):
        _, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [BY_SETTLEMENT]), sure(["E1", "E2", "E3"])])
        first_look = self.reasoner.calls[0][1]
        second_look = self.reasoner.calls[1][1]
        self.assertEqual([e.label for e in first_look], ["E1"])
        self.assertEqual([e.label for e in second_look], ["E1", "E2", "E3"])
        self.assertEqual(second_look[0].item, first_look[0].item, "E1 means the same row on both looks")
        self.assertEqual(
            sorted(e.item.data["transaction_id"] for e in second_look),
            ["BANK-SPLIT-001-A", "BANK-SPLIT-001-B", "TXN-SPLIT-001"],
        )
        self.assertEqual([ref.kind for ref in outcome.cited_references], ["transaction"] * 3)
        self.assertEqual(len(set(outcome.cited_references)), 3)

    def test_write_back_describes_each_row_once(self):
        case_id, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [BY_SETTLEMENT]), sure(["E1", "E2", "E3"])])
        self.assertEqual(outcome.status, "RESOLVED")
        description, root_cause = self.saved(case_id)
        self.assertIn("PAYMENT_GATEWAY: 1 row, total 10000.00 INR", description)
        self.assertIn("BANK: 2 rows, total 10000.00 INR", description)
        self.assertIn("amount gap 0.00 INR", description)
        self.assertEqual(root_cause, "Split settlement paid in two bank transfers")


class RepeatedIdsTest(LoopTestBase):
    def test_same_id_twice_in_request_is_looked_up_once(self):
        case_id, outcome = self.run_case(
            ["TXN-FEE-001", "BANK-FEE-001", "TXN-FEE-001"], [sure(["E1", "E2"], "Gateway fee deducted")]
        )
        search_text = self.embedder.calls[0]
        self.assertIn("PAYMENT_GATEWAY: 1 row, total 5000.00 INR", search_text)
        self.assertIn("amount gap 75.00 INR", search_text)
        self.assertEqual([e.label for e in self.reasoner.calls[0][1]], ["E1", "E2"])
        self.assertEqual(len(outcome.evidence), 2)
        self.assertIn("amount gap 75.00 INR", self.saved(case_id)[0])


class RetryRulesTest(LoopTestBase):
    def test_bad_tool_key_pair_alone_does_not_retry(self):
        no_key = FetchRequest(tool="get_transaction_group", key=None, value="SET-SPLIT-001")
        _, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [no_key]), sure(["E1"])])
        self.assertEqual(outcome.attempts, 1)

    def test_directed_similar_cases_search_uses_the_requested_text(self):
        searches: list[str] = []

        def recording_find(text, k=5, *, embedder=None, conn=None):
            searches.append(text)
            return []

        ask = FetchRequest(tool="find_similar_cases", key=None, value="gateway paid once, bank paid in two parts")
        _, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [ask]), unsure(["E1"])], find_similar_cases=recording_find)
        self.assertEqual(len(searches), 2)
        self.assertEqual(searches[1], ask.value)
        self.assertEqual(outcome.attempts, 2)

    def test_tool_failure_on_retry_is_raised(self):
        def failing_on_settlement(key, value, conn=None):
            if key == "settlement_id":
                raise psycopg.OperationalError("database went away")
            return real_get_transaction_group(key, value)

        with self.assertRaises(psycopg.OperationalError):
            self.run_case(
                ["TXN-SPLIT-001"],
                [unsure(["E1"], [BY_SETTLEMENT]), sure(["E1"])],
                get_transaction_group=failing_on_settlement,
            )
        self.assertEqual(len(self.reasoner.calls), 1)

    def test_refusal_on_second_look_is_raised(self):
        with self.assertRaises(LLMRefusedError):
            self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [BY_SETTLEMENT]), LLMRefusedError("blocked")])
        self.assertEqual(len(self.reasoner.calls), 2)

    def test_retry_that_finds_nothing_still_gets_a_second_look(self):
        ghost = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-NOT-THERE-" + uuid.uuid4().hex)
        _, outcome = self.run_case(["TXN-SPLIT-001"], [unsure(["E1"], [ghost]), unsure(["E1"])])
        self.assertEqual(outcome.attempts, 2)
        self.assertEqual([e.label for e in self.reasoner.calls[1][1]], ["E1"])
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertIsInstance(outcome.cited_references[0], EvidenceRef)


if __name__ == "__main__":
    unittest.main()
