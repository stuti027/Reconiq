"""Tests for evidence labels. Most use hand-built items; one class uses real rows from the sandbox database."""

import unittest

from app.agent.evidence import EvidenceItem, EvidenceRef, label_evidence, split_citations
from app.agent.reasoning import FetchRequest
from app.tools.transactions import get_transaction_group

BY_ID = FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN-SPLIT-001")
BY_SETTLEMENT = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")
SIMILAR = FetchRequest(tool="find_similar_cases", key=None, value="gateway 10000 INR, bank 6000 INR")


def row(event_id: str, fetch: FetchRequest = BY_ID) -> EvidenceItem:
    return EvidenceItem.from_row(fetch, {"event_id": event_id, "amount_value": "1.00"})


def case(case_id: str) -> EvidenceItem:
    return EvidenceItem.from_past_case(SIMILAR, {"case_id": case_id, "root_cause": "fee", "distance": 0.1})


class LabelEvidenceTest(unittest.TestCase):
    def test_numbers_in_order(self):
        labelled, label_map = label_evidence([row("evt-a"), row("evt-b"), case("case-1")])
        self.assertEqual([e.label for e in labelled], ["E1", "E2", "E3"])
        self.assertEqual(label_map, {
            "E1": EvidenceRef("transaction", "evt-a"),
            "E2": EvidenceRef("transaction", "evt-b"),
            "E3": EvidenceRef("past_case", "case-1"),
        })

    def test_items_keep_their_fetch_and_data(self):
        labelled, _ = label_evidence([row("evt-a"), case("case-1")])
        self.assertEqual(labelled[0].item.fetch, BY_ID)
        self.assertEqual(labelled[0].item.data["event_id"], "evt-a")
        self.assertEqual(labelled[1].item.fetch.tool, "find_similar_cases")
        self.assertEqual(labelled[1].item.data["root_cause"], "fee")

    def test_same_row_twice_gets_one_label(self):
        labelled, label_map = label_evidence([row("evt-a"), row("evt-a", BY_SETTLEMENT), row("evt-b", BY_SETTLEMENT)])
        self.assertEqual([e.label for e in labelled], ["E1", "E2"])
        self.assertEqual(labelled[0].item.fetch, BY_ID, "first fetch that found the row is kept")
        self.assertEqual(label_map["E2"].ref_id, "evt-b")

    def test_pass_one_labels_survive_pass_two(self):
        pass_one = [row("evt-a"), case("case-1")]
        pass_two = [row("evt-a", BY_SETTLEMENT), row("evt-b", BY_SETTLEMENT), row("evt-c", BY_SETTLEMENT)]
        before, map_before = label_evidence(pass_one)
        after, map_after = label_evidence(pass_one + pass_two)
        self.assertEqual(after[: len(before)], before)
        for label, ref in map_before.items():
            self.assertEqual(map_after[label], ref)
        self.assertEqual([e.label for e in after], ["E1", "E2", "E3", "E4"])

    def test_row_and_case_with_same_id_are_different(self):
        labelled, label_map = label_evidence([row("X-1"), case("X-1")])
        self.assertEqual(len(labelled), 2)
        self.assertEqual({ref.kind for ref in label_map.values()}, {"transaction", "past_case"})

    def test_same_input_same_labels(self):
        items = [row("evt-a"), case("case-1"), row("evt-b")]
        self.assertEqual(label_evidence(items), label_evidence(list(items)))

    def test_empty(self):
        self.assertEqual(label_evidence([]), ([], {}))


class SplitCitationsTest(unittest.TestCase):
    def setUp(self):
        _, self.label_map = label_evidence([row("evt-a"), row("evt-b"), case("case-1")])

    def test_known_labels(self):
        refs, unknown = split_citations(["E3", "E1"], self.label_map)
        self.assertEqual(refs, [EvidenceRef("past_case", "case-1"), EvidenceRef("transaction", "evt-a")])
        self.assertEqual(unknown, [])

    def test_unknown_labels(self):
        refs, unknown = split_citations(["E1", "E9", "evt-b", "E9"], self.label_map)
        self.assertEqual(refs, [EvidenceRef("transaction", "evt-a")])
        self.assertEqual(unknown, ["E9", "evt-b"])

    def test_repeats_collapse(self):
        refs, _ = split_citations(["E2", "E2", "E2"], self.label_map)
        self.assertEqual(refs, [EvidenceRef("transaction", "evt-b")])

    def test_nothing_cited(self):
        self.assertEqual(split_citations([], self.label_map), ([], []))


class RealRowsTest(unittest.TestCase):
    """SPLIT: pass 1 sees only the gateway row; pass 2 by settlement finds the two bank rows and the gateway row again."""

    def test_split_case_labels(self):
        pass_one = [EvidenceItem.from_row(BY_ID, r) for r in get_transaction_group("transaction_id", "TXN-SPLIT-001")]
        pass_two = [EvidenceItem.from_row(BY_SETTLEMENT, r) for r in get_transaction_group("settlement_id", "SET-SPLIT-001")]
        self.assertEqual(len(pass_one), 1)
        self.assertEqual(len(pass_two), 3)

        labelled, label_map = label_evidence(pass_one + pass_two)
        self.assertEqual(len(labelled), 3, "the gateway row is labelled once")
        self.assertEqual(labelled[0].item.data["transaction_id"], "TXN-SPLIT-001")
        self.assertEqual(labelled[0].item.fetch, BY_ID)
        self.assertEqual(
            sorted(e.item.data["transaction_id"] for e in labelled[1:]),
            ["BANK-SPLIT-001-A", "BANK-SPLIT-001-B"],
        )
        self.assertEqual(label_map["E1"].ref_id, pass_one[0].data["event_id"])


if __name__ == "__main__":
    unittest.main()
