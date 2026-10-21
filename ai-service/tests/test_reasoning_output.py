"""Tests for the reasoning output models, the reasoner errors and the scripted reasoner. No database, no network."""

import unittest

from pydantic import ValidationError

from app.agent.evidence import EvidenceItem, EvidenceRef, LabelledEvidence
from app.agent.fakes import ScriptedReasoner
from app.agent.reasoning import (
    FetchRequest,
    LLMOutputInvalidError,
    LLMRefusedError,
    ReasonerError,
    ReasoningOutput,
)
from app.contracts import InvestigationRequest


def answer(**overrides) -> dict:
    base = {"confident": True, "root_cause": "Gateway fee deducted before settlement", "cited_evidence": ["E1", "E2"], "missing": []}
    base.update(overrides)
    return base


class FetchRequestTest(unittest.TestCase):
    def test_transaction_lookup(self):
        fetch = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")
        self.assertEqual((fetch.tool, fetch.key, fetch.value), ("get_transaction_group", "settlement_id", "SET-SPLIT-001"))

    def test_similar_cases_has_no_key(self):
        fetch = FetchRequest(tool="find_similar_cases", key=None, value="gateway 5000 INR, bank 4925 INR")
        self.assertIsNone(fetch.key)

    def test_unknown_tool_refused(self):
        with self.assertRaises(ValidationError):
            FetchRequest(tool="run_sql", key=None, value="SELECT 1")

    def test_unknown_key_refused(self):
        for key in ("merchant_id", "event_id", "transaction_id; DROP TABLE x", ""):
            with self.subTest(key=key), self.assertRaises(ValidationError):
                FetchRequest(tool="get_transaction_group", key=key, value="x")

    def test_empty_value_refused(self):
        with self.assertRaises(ValidationError):
            FetchRequest(tool="get_transaction_group", key="transaction_id", value="")

    def test_extra_field_refused(self):
        with self.assertRaises(ValidationError):
            FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN00011", limit=500)

    def test_key_is_required_even_when_none(self):
        with self.assertRaises(ValidationError):
            FetchRequest(tool="find_similar_cases", value="text")

    def test_frozen_and_hashable(self):
        a = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-FEE-001")
        b = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-FEE-001")
        self.assertEqual(len({a, b}), 1)
        with self.assertRaises(ValidationError):
            a.value = "SET-SPLIT-001"


class ReasoningOutputTest(unittest.TestCase):
    def test_confident_answer(self):
        out = ReasoningOutput(**answer())
        self.assertTrue(out.confident)
        self.assertEqual(out.cited_evidence, ["E1", "E2"])
        self.assertEqual(out.missing, [])

    def test_not_confident_with_fetch_request(self):
        out = ReasoningOutput(**answer(
            confident=False,
            root_cause=None,
            cited_evidence=["E1"],
            missing=[{"tool": "get_transaction_group", "key": "settlement_id", "value": "SET-SPLIT-001"}],
        ))
        self.assertFalse(out.confident)
        self.assertEqual(out.missing[0], FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001"))

    def test_not_confident_may_still_suggest_a_cause(self):
        out = ReasoningOutput(**answer(confident=False, root_cause="Possibly a split settlement"))
        self.assertEqual(out.root_cause, "Possibly a split settlement")

    def test_confident_needs_root_cause(self):
        for root_cause in (None, "", "   "):
            with self.subTest(root_cause=root_cause), self.assertRaises(ValidationError):
                ReasoningOutput(**answer(root_cause=root_cause))

    def test_every_field_required(self):
        for field in ("confident", "root_cause", "cited_evidence", "missing"):
            data = answer()
            del data[field]
            with self.subTest(field=field), self.assertRaises(ValidationError):
                ReasoningOutput(**data)

    def test_extra_field_refused(self):
        with self.assertRaises(ValidationError):
            ReasoningOutput(**answer(confidence_score=0.9))

    def test_bad_fetch_request_fails_whole_answer(self):
        with self.assertRaises(ValidationError):
            ReasoningOutput(**answer(confident=False, missing=[{"tool": "delete_rows", "key": None, "value": "x"}]))

    def test_parses_json_text(self):
        text = (
            '{"confident": false, "root_cause": null, "cited_evidence": ["E1"],'
            ' "missing": [{"tool": "get_transaction_group", "key": "settlement_id", "value": "SET-SPLIT-001"}]}'
        )
        out = ReasoningOutput.model_validate_json(text)
        self.assertEqual(out.missing[0].key, "settlement_id")

    def test_malformed_json_refused(self):
        with self.assertRaises(ValidationError):
            ReasoningOutput.model_validate_json('{"confident": true, "root_cause": "fee"')


class ReasonerErrorTest(unittest.TestCase):
    def test_reason_codes(self):
        self.assertEqual(LLMOutputInvalidError.reason_code, "LLM_OUTPUT_INVALID")
        self.assertEqual(LLMRefusedError.reason_code, "LLM_REFUSED")

    def test_common_base(self):
        for error in (LLMOutputInvalidError("bad"), LLMRefusedError("blocked")):
            self.assertIsInstance(error, ReasonerError)


class ScriptedReasonerTest(unittest.TestCase):
    def setUp(self):
        self.request = InvestigationRequest(case_id="unit-1", source_phase="PHASE_3", transaction_ids=["TXN-FEE-001"])
        item = EvidenceItem(
            fetch=FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN-FEE-001"),
            ref=EvidenceRef("transaction", "evt-1"),
            data={"event_id": "evt-1"},
        )
        self.evidence = [LabelledEvidence("E1", item)]

    def test_replays_in_order_and_records_calls(self):
        first = ReasoningOutput(**answer(confident=False, root_cause=None))
        second = ReasoningOutput(**answer())
        reasoner = ScriptedReasoner([first, second])
        self.assertIs(reasoner.reason(self.request, self.evidence), first)
        self.assertIs(reasoner.reason(self.request, self.evidence), second)
        self.assertEqual(len(reasoner.calls), 2)
        self.assertEqual(reasoner.calls[0][0].case_id, "unit-1")
        self.assertEqual([e.label for e in reasoner.calls[0][1]], ["E1"])

    def test_records_a_copy_of_the_evidence(self):
        reasoner = ScriptedReasoner([ReasoningOutput(**answer())])
        evidence = list(self.evidence)
        reasoner.reason(self.request, evidence)
        evidence.append(evidence[0])
        self.assertEqual(len(reasoner.calls[0][1]), 1)

    def test_raises_scripted_error(self):
        reasoner = ScriptedReasoner([LLMOutputInvalidError("schema failed twice")])
        with self.assertRaises(LLMOutputInvalidError):
            reasoner.reason(self.request, self.evidence)

    def test_running_out_is_a_test_failure(self):
        reasoner = ScriptedReasoner([])
        with self.assertRaises(AssertionError):
            reasoner.reason(self.request, self.evidence)

    def test_has_model_name(self):
        self.assertEqual(ScriptedReasoner([]).model, "scripted-test-reasoner")
        self.assertEqual(ScriptedReasoner([], model="other").model, "other")


if __name__ == "__main__":
    unittest.main()
