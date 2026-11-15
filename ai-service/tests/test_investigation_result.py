"""The result sent to Phase 5: per-status rules, JSON shape, and building it from a graph outcome or a failure."""

import json
import unittest
import uuid
from datetime import datetime, timedelta, timezone

import psycopg
from pydantic import ValidationError

from app.agent.evidence import EvidenceItem, EvidenceRef
from app.agent.fakes import ScriptedReasoner
from app.agent.graph import run_investigation
from app.agent.reasoning import FetchRequest, ReasoningOutput
from app.agent.state import InvestigationOutcome
from app.contracts import InvestigationRequest
from app.main import connect
from app.result import EvidenceReference, InvestigationResult, failed_result, result_from_outcome
from tests.test_agent_loop import AnyTextEmbedder

AT = datetime(2026, 11, 11, 8, 30, tzinfo=timezone.utc)
ROW_A = EvidenceReference(kind="transaction", ref_id="evt_gw_fee_001")
ROW_B = EvidenceReference(kind="transaction", ref_id="evt_bank_fee_001")
PREFIX = "result-contract-test-"


def fields(**overrides) -> dict:
    base = {
        "case_id": "case-1",
        "source_phase": "PHASE_3",
        "transaction_ids": ["TXN-FEE-001", "BANK-FEE-001"],
        "status": "RESOLVED",
        "root_cause": "Gateway fee deducted before settlement",
        "cited_evidence": [ROW_A, ROW_B],
        "examined_evidence": [ROW_A, ROW_B],
        "failure_reason": None,
        "attempts": 1,
        "model": "gemini-test",
        "investigated_at": AT,
    }
    base.update(overrides)
    return base


def request(transaction_ids: list[str], case_id: str = "case-1") -> InvestigationRequest:
    return InvestigationRequest(case_id=case_id, source_phase="PHASE_3", transaction_ids=transaction_ids)


def item(ref_id: str, key: str = "transaction_id") -> EvidenceItem:
    fetch = FetchRequest(tool="get_transaction_group", key=key, value="X")
    return EvidenceItem(fetch=fetch, ref=EvidenceRef("transaction", ref_id), data={"event_id": ref_id})


class StatusRulesTest(unittest.TestCase):
    def test_resolved_result(self):
        result = InvestigationResult(**fields())
        self.assertEqual(result.status, "RESOLVED")
        self.assertEqual(result.schema_version, 1)

    def test_resolved_needs_a_root_cause(self):
        for cause in (None, "", "   "):
            with self.subTest(cause=cause), self.assertRaises(ValidationError):
                InvestigationResult(**fields(root_cause=cause))

    def test_resolved_needs_a_citation(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(cited_evidence=[]))

    def test_resolved_has_no_failure_reason(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(failure_reason="LLM_REFUSED"))

    def test_low_confidence_root_cause_is_optional(self):
        for cause in (None, "Possibly a split settlement"):
            with self.subTest(cause=cause):
                result = InvestigationResult(**fields(status="LOW_CONFIDENCE", root_cause=cause, cited_evidence=[]))
                self.assertEqual(result.root_cause, cause)

    def test_low_confidence_has_no_failure_reason(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(status="LOW_CONFIDENCE", failure_reason="LLM_REFUSED"))

    def test_failed_result(self):
        result = InvestigationResult(
            **fields(status="FAILED", root_cause=None, cited_evidence=[], failure_reason="LLM_REFUSED", attempts=0)
        )
        self.assertEqual(result.failure_reason, "LLM_REFUSED")

    def test_failed_needs_a_failure_reason(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(status="FAILED", root_cause=None, cited_evidence=[]))

    def test_failed_has_no_root_cause(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(status="FAILED", cited_evidence=[], failure_reason="LLM_REFUSED"))

    def test_failure_reason_is_a_code(self):
        for reason in ("llm refused", "LLM-REFUSED", "", "1ABC"):
            with self.subTest(reason=reason), self.assertRaises(ValidationError):
                InvestigationResult(**fields(status="FAILED", root_cause=None, cited_evidence=[], failure_reason=reason))

    def test_reasoned_results_need_at_least_one_reasoning_call(self):
        for status in ("RESOLVED", "LOW_CONFIDENCE"):
            with self.subTest(status=status), self.assertRaises(ValidationError):
                InvestigationResult(**fields(status=status, attempts=0))

    def test_attempts_stay_within_the_reasoning_limit(self):
        for attempts in (-1, 3):
            with self.subTest(attempts=attempts), self.assertRaises(ValidationError):
                InvestigationResult(**fields(attempts=attempts))

    def test_every_cited_reference_was_examined(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(examined_evidence=[ROW_A]))


class ContractShapeTest(unittest.TestCase):
    def test_extra_fields_are_refused(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(severity="HIGH"))

    def test_only_schema_version_1_exists(self):
        with self.assertRaises(ValidationError):
            InvestigationResult(**fields(schema_version=2))

    def test_timestamp_must_be_utc(self):
        naive = datetime(2026, 11, 11, 8, 30)
        india = datetime(2026, 11, 11, 14, 0, tzinfo=timezone(timedelta(hours=5, minutes=30)))
        for value in (naive, india):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                InvestigationResult(**fields(investigated_at=value))

    def test_json_shape(self):
        data = json.loads(InvestigationResult(**fields()).model_dump_json())
        self.assertEqual(
            set(data),
            {
                "schema_version", "case_id", "source_phase", "transaction_ids", "status", "root_cause",
                "cited_evidence", "examined_evidence", "failure_reason", "attempts", "model", "investigated_at",
            },
        )
        self.assertEqual(data["investigated_at"], "2026-11-11T08:30:00Z")
        self.assertEqual(data["cited_evidence"][0], {"kind": "transaction", "ref_id": "evt_gw_fee_001"})
        self.assertFalse(any(isinstance(value, float) for value in data.values()), "no float in the payload")

    def test_json_round_trip(self):
        result = InvestigationResult(**fields())
        self.assertEqual(InvestigationResult.model_validate_json(result.model_dump_json()), result)

    def test_result_cannot_be_changed(self):
        result = InvestigationResult(**fields())
        with self.assertRaises(ValidationError):
            result.status = "FAILED"


class BuildersTest(unittest.TestCase):
    def test_result_from_outcome(self):
        outcome = InvestigationOutcome(
            status="RESOLVED",
            root_cause="Gateway fee deducted",
            cited_references=[EvidenceRef("transaction", "a")],
            attempts=1,
            evidence=[item("a"), item("b")],
            model="scripted",
        )
        result = result_from_outcome(request(["TXN-1"]), outcome, investigated_at=AT)
        self.assertEqual(result.case_id, "case-1")
        self.assertEqual(result.transaction_ids, ["TXN-1"])
        self.assertEqual([r.ref_id for r in result.cited_evidence], ["a"])
        self.assertEqual([r.ref_id for r in result.examined_evidence], ["a", "b"])
        self.assertIsNone(result.failure_reason)
        self.assertEqual(result.investigated_at, AT)

    def test_a_row_found_twice_is_examined_once(self):
        outcome = InvestigationOutcome(
            status="LOW_CONFIDENCE",
            root_cause=None,
            cited_references=[],
            attempts=2,
            evidence=[item("gw"), item("gw", key="settlement_id"), item("bank")],
            model="scripted",
        )
        result = result_from_outcome(request(["TXN-1"]), outcome, investigated_at=AT)
        self.assertEqual([r.ref_id for r in result.examined_evidence], ["gw", "bank"])

    def test_timestamp_defaults_to_now_in_utc(self):
        outcome = InvestigationOutcome("LOW_CONFIDENCE", None, [], 1, [], "scripted")
        before = datetime.now(timezone.utc)
        result = result_from_outcome(request(["TXN-1"]), outcome)
        self.assertLessEqual(before, result.investigated_at)
        self.assertEqual(result.investigated_at.utcoffset(), timedelta(0))

    def test_failed_result(self):
        result = failed_result(
            request(["TXN-1"]), failure_reason="LLM_REFUSED", model="gemini-test", attempts=1, evidence=[item("a")],
            investigated_at=AT,
        )
        self.assertEqual(result.status, "FAILED")
        self.assertIsNone(result.root_cause)
        self.assertEqual(result.cited_evidence, [])
        self.assertEqual([r.ref_id for r in result.examined_evidence], ["a"])

    def test_failed_before_any_evidence(self):
        result = failed_result(request(["TXN-1"]), failure_reason="POISON_CASE", model="gemini-test")
        self.assertEqual(result.attempts, 0)
        self.assertEqual(result.examined_evidence, [])


class RealGraphTest(unittest.TestCase):
    """The SPLIT case through the graph on real sandbox rows, then into the result."""

    def tearDown(self) -> None:
        try:
            with connect() as conn:
                conn.execute("DELETE FROM investigation.past_cases WHERE case_id LIKE %s", (PREFIX + "%",))
        except psycopg.errors.UndefinedTable:
            pass

    def test_split_case_result(self):
        by_settlement = FetchRequest(tool="get_transaction_group", key="settlement_id", value="SET-SPLIT-001")
        reasoner = ScriptedReasoner([
            ReasoningOutput(confident=False, root_cause=None, cited_evidence=["E1"], missing=[by_settlement]),
            ReasoningOutput(confident=True, root_cause="Split settlement", cited_evidence=["E1", "E2", "E3"], missing=[]),
        ])
        req = request(["TXN-SPLIT-001"], case_id=PREFIX + uuid.uuid4().hex)
        outcome = run_investigation(req, reasoner=reasoner, embedder=AnyTextEmbedder())

        result = result_from_outcome(req, outcome, investigated_at=AT)

        self.assertEqual(result.status, "RESOLVED")
        self.assertEqual(result.attempts, 2)
        expected = ["evt_gw_split_001", "evt_bank_split_001a", "evt_bank_split_001b"]
        self.assertEqual(sorted(r.ref_id for r in result.examined_evidence), sorted(expected))
        self.assertEqual(sorted(r.ref_id for r in result.cited_evidence), sorted(expected))
        self.assertEqual(InvestigationResult.model_validate_json(result.model_dump_json()), result)


if __name__ == "__main__":
    unittest.main()
