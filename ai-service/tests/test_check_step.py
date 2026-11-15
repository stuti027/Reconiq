"""Table-style tests for the check step (guard + loop condition). Plain code: no database, no network."""

import unittest

from app.agent.check import (
    MAX_FETCHES_PER_RETRY,
    MAX_REASONING_CALLS,
    check_reasoning,
    is_runnable,
)
from app.agent.evidence import EvidenceRef
from app.agent.reasoning import FetchRequest, ReasoningOutput

LABELS = {
    "E1": EvidenceRef("transaction", "evt-gateway"),
    "E2": EvidenceRef("transaction", "evt-bank"),
    "E3": EvidenceRef("past_case", "case-1"),
}
BY_ID = FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN-SPLIT-001")
SIMILAR = FetchRequest(tool="find_similar_cases", key=None, value="only PAYMENT_GATEWAY rows")
FETCHED = {BY_ID, SIMILAR}
BY_SETTLEMENT = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")
BY_REFERENCE = FetchRequest(tool="get_transaction_group", key="reference_key", value="REFSPLIT001")


def out(confident: bool, cited: list[str], missing: list[FetchRequest] = ()) -> ReasoningOutput:
    return ReasoningOutput(
        confident=confident,
        root_cause="Split settlement paid in two parts" if confident else None,
        cited_evidence=cited,
        missing=list(missing),
    )


class GuardTest(unittest.TestCase):
    def test_confident_with_known_citations_stands(self):
        result = check_reasoning(out(True, ["E1", "E2"]), LABELS, FETCHED, 1)
        self.assertEqual((result.confident, result.overridden, result.decision), (True, False, "finalize"))

    def test_overridden_cases(self):
        rows = [
            ("cites nothing", []),
            ("cites a label never shown", ["E1", "E9"]),
            ("cites a raw id instead of a label", ["evt-gateway"]),
            ("cites only unknown labels", ["E7"]),
        ]
        for name, cited in rows:
            with self.subTest(name):
                result = check_reasoning(out(True, cited), LABELS, FETCHED, 1)
                self.assertFalse(result.confident)
                self.assertTrue(result.overridden)
                self.assertEqual(result.decision, "finalize")

    def test_not_confident_is_never_marked_overridden(self):
        result = check_reasoning(out(False, []), LABELS, FETCHED, 1)
        self.assertFalse(result.overridden)

    def test_a_past_case_alone_counts_as_a_citation(self):
        result = check_reasoning(out(True, ["E3"]), LABELS, FETCHED, 1)
        self.assertTrue(result.confident)


class LoopConditionTest(unittest.TestCase):
    def test_decision_table(self):
        rows = [
            # name, answer, attempts, expected decision, expected fetches
            ("confident -> finalize", out(True, ["E1"]), 1, "finalize", ()),
            ("confident but still asks -> finalize", out(True, ["E1"], [BY_SETTLEMENT]), 1, "finalize", ()),
            ("unsure, nothing asked -> finalize", out(False, ["E1"]), 1, "finalize", ()),
            ("unsure, new fetch -> retry", out(False, ["E1"], [BY_SETTLEMENT]), 1, "retry", (BY_SETTLEMENT,)),
            ("unsure, only repeats -> finalize", out(False, ["E1"], [BY_ID, SIMILAR]), 1, "finalize", ()),
            ("unsure, repeat + new -> retry new", out(False, ["E1"], [BY_ID, BY_SETTLEMENT]), 1, "retry", (BY_SETTLEMENT,)),
            ("unsure, new fetch, cap reached -> finalize", out(False, ["E1"], [BY_SETTLEMENT]), 2, "finalize", ()),
            ("overridden + new fetch -> retry", out(True, [], [BY_SETTLEMENT]), 1, "retry", (BY_SETTLEMENT,)),
        ]
        for name, answer, attempts, decision, fetches in rows:
            with self.subTest(name):
                result = check_reasoning(answer, LABELS, FETCHED, attempts)
                self.assertEqual(result.decision, decision)
                self.assertEqual(result.next_fetches, fetches)

    def test_never_retries_at_or_past_the_cap(self):
        answer = out(False, ["E1"], [BY_SETTLEMENT, BY_REFERENCE])
        for attempts in range(MAX_REASONING_CALLS, MAX_REASONING_CALLS + 4):
            with self.subTest(attempts=attempts):
                self.assertEqual(check_reasoning(answer, LABELS, FETCHED, attempts).decision, "finalize")
        self.assertEqual(MAX_REASONING_CALLS, 2, "pass 1 plus one retry")

    def test_bad_tool_key_pairs_are_skipped(self):
        no_key = FetchRequest(tool="get_transaction_group", key=None, value="SET-SPLIT-001")
        keyed_search = FetchRequest(tool="find_similar_cases", key="settlement_id", value="split")
        only_bad = check_reasoning(out(False, ["E1"], [no_key, keyed_search]), LABELS, FETCHED, 1)
        self.assertEqual((only_bad.decision, only_bad.next_fetches), ("finalize", ()))
        mixed = check_reasoning(out(False, ["E1"], [no_key, BY_SETTLEMENT]), LABELS, FETCHED, 1)
        self.assertEqual(mixed.next_fetches, (BY_SETTLEMENT,))

    def test_repeated_requests_collapse_in_order(self):
        result = check_reasoning(
            out(False, ["E1"], [BY_REFERENCE, BY_SETTLEMENT, BY_REFERENCE]), LABELS, FETCHED, 1
        )
        self.assertEqual(result.next_fetches, (BY_REFERENCE, BY_SETTLEMENT))

    def test_retry_size_is_capped(self):
        many = [FetchRequest(tool="get_transaction_group", key="transaction_id", value=f"TXN-{i}") for i in range(8)]
        result = check_reasoning(out(False, ["E1"], many), LABELS, FETCHED, 1)
        self.assertEqual(result.next_fetches, tuple(many[:MAX_FETCHES_PER_RETRY]))

    def test_new_similar_cases_search_is_allowed(self):
        other_text = FetchRequest(tool="find_similar_cases", key=None, value="two bank rows, one gateway row")
        result = check_reasoning(out(False, ["E1"], [other_text]), LABELS, FETCHED, 1)
        self.assertEqual(result.next_fetches, (other_text,))


class IsRunnableTest(unittest.TestCase):
    def test_pairs(self):
        rows = [
            (FetchRequest(tool="get_transaction_group", key="transaction_id", value="x"), True),
            (FetchRequest(tool="get_transaction_group", key="settlement_id", value="x"), True),
            (FetchRequest(tool="get_transaction_group", key="reference_key", value="x"), True),
            (FetchRequest(tool="get_transaction_group", key=None, value="x"), False),
            (FetchRequest(tool="find_similar_cases", key=None, value="x"), True),
            (FetchRequest(tool="find_similar_cases", key="transaction_id", value="x"), False),
        ]
        for fetch, expected in rows:
            with self.subTest(tool=fetch.tool, key=fetch.key):
                self.assertEqual(is_runnable(fetch), expected)


if __name__ == "__main__":
    unittest.main()
