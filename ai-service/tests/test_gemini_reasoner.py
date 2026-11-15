"""Gemini reasoner: fake client for ordinary tests; one opt-in live call."""

import os
import unittest
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

from dotenv import load_dotenv
from pydantic import ValidationError

from app.agent.evidence import EvidenceItem, EvidenceRef, LabelledEvidence
from app.agent.fakes import ScriptedReasoner
from app.agent.graph import run_investigation
from app.agent.reasoning import (
    FetchRequest,
    GeminiReasoner,
    LLMOutputInvalidError,
    LLMRefusedError,
    ReasoningOutput,
)
from app.contracts import InvestigationRequest

VALID_JSON = (
    '{"confident": true, "root_cause": "Gateway fee deducted before settlement",'
    ' "cited_evidence": ["E1"], "missing": []}'
)
UNSURE_JSON = '{"confident": false, "root_cause": null, "cited_evidence": ["E1"], "missing": []}'


def sample_request() -> InvestigationRequest:
    return InvestigationRequest(
        case_id="unit-gemini-1",
        source_phase="PHASE_3",
        transaction_ids=["TXN-FEE-001"],
        context={"kind": "fee"},
    )


def sample_evidence() -> list[LabelledEvidence]:
    fetch = FetchRequest(tool="get_transaction_group", key="transaction_id", value="TXN-FEE-001")
    item = EvidenceItem(
        fetch=fetch,
        ref=EvidenceRef("transaction", "evt-1"),
        data={
            "event_id": "evt-1",
            "transaction_id": "TXN-FEE-001",
            "amount_value": Decimal("5000.00"),
            "event_timestamp": datetime(2026, 1, 2, 8, 0, tzinfo=timezone.utc),
        },
    )
    return [LabelledEvidence("E1", item)]


class FakeModels:
    def __init__(self, script: list) -> None:
        self._script = list(script)
        self.calls: list[dict] = []

    def generate_content(self, **kwargs):
        self.calls.append(kwargs)
        if not self._script:
            raise AssertionError("Fake Gemini client ran out of responses")
        item = self._script.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


class FakeGeminiClient:
    def __init__(self, script: list) -> None:
        self.models = FakeModels(script)


class FakeResponse:
    def __init__(self, text=None, *, block_reason=None, finish_reason="STOP", candidates=None) -> None:
        self.text = text
        self.prompt_feedback = SimpleNamespace(block_reason=block_reason) if block_reason is not None else None
        if candidates is not None:
            self.candidates = candidates
        elif block_reason is not None:
            self.candidates = []
        else:
            self.candidates = [SimpleNamespace(finish_reason=finish_reason)]


class GeminiReasonerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.request = sample_request()
        self.evidence = sample_evidence()

    def reasoner(self, script: list) -> GeminiReasoner:
        client = FakeGeminiClient(script)
        self.client = client
        return GeminiReasoner(client=client, model="test-gemini")

    def test_valid_json_produces_reasoning_output(self) -> None:
        out = self.reasoner([FakeResponse(VALID_JSON)]).reason(self.request, self.evidence)
        self.assertIsInstance(out, ReasoningOutput)
        self.assertTrue(out.confident)
        self.assertEqual(out.root_cause, "Gateway fee deducted before settlement")
        self.assertEqual(out.cited_evidence, ["E1"])
        call = self.client.models.calls[0]
        self.assertEqual(call["model"], "test-gemini")
        self.assertEqual(call["config"].temperature, 0)
        self.assertEqual(call["config"].response_mime_type, "application/json")
        schema = call["config"].response_schema
        json_schema = call["config"].response_json_schema
        self.assertTrue(schema is ReasoningOutput or schema is not None or json_schema is not None)

    def test_schema_is_sent_as_json_schema(self) -> None:
        self.reasoner([FakeResponse(VALID_JSON)]).reason(self.request, self.evidence)
        config = self.client.models.calls[0]["config"]
        self.assertIsNone(config.response_schema, "Gemini's response_schema rejects additionalProperties")
        self.assertEqual(config.response_json_schema, ReasoningOutput.model_json_schema())

    def test_invalid_then_valid_succeeds(self) -> None:
        out = self.reasoner([FakeResponse("not-json"), FakeResponse(VALID_JSON)]).reason(self.request, self.evidence)
        self.assertTrue(out.confident)
        self.assertEqual(len(self.client.models.calls), 2)

    def test_two_invalid_responses_raise_llm_output_invalid(self) -> None:
        reasoner = self.reasoner([FakeResponse("{"), FakeResponse('{"confident": true}')])
        with self.assertRaises(LLMOutputInvalidError):
            reasoner.reason(self.request, self.evidence)
        self.assertEqual(len(self.client.models.calls), 2)

    def test_blocked_response_raises_llm_refused(self) -> None:
        reasoner = self.reasoner([FakeResponse(text=None, block_reason="SAFETY"), FakeResponse(VALID_JSON)])
        with self.assertRaises(LLMRefusedError):
            reasoner.reason(self.request, self.evidence)
        self.assertEqual(len(self.client.models.calls), 1, "a refusal must not be retried as invalid JSON")

    def test_safety_finish_reason_raises_llm_refused(self) -> None:
        reasoner = self.reasoner([FakeResponse(text="{}", finish_reason="SAFETY")])
        with self.assertRaises(LLMRefusedError):
            reasoner.reason(self.request, self.evidence)

    def test_decimal_and_datetime_evidence_are_serialized_into_the_prompt(self) -> None:
        self.reasoner([FakeResponse(UNSURE_JSON)]).reason(self.request, self.evidence)
        prompt = self.client.models.calls[0]["contents"]
        self.assertIn("5000.00", prompt)
        self.assertIn("2026-01-02T08:00:00+00:00", prompt)
        self.assertIn("E1:", prompt)

    def test_api_errors_are_not_retried(self) -> None:
        class QuotaError(Exception):
            pass

        reasoner = self.reasoner([QuotaError("429")])
        with self.assertRaises(QuotaError):
            reasoner.reason(self.request, self.evidence)
        self.assertEqual(len(self.client.models.calls), 1)

    def test_schema_invalid_json_is_treated_as_invalid_output(self) -> None:
        bad = '{"confident": true, "root_cause": "", "cited_evidence": [], "missing": []}'
        reasoner = self.reasoner([FakeResponse(bad), FakeResponse(bad)])
        with self.assertRaises(LLMOutputInvalidError) as ctx:
            reasoner.reason(self.request, self.evidence)
        self.assertIsInstance(ctx.exception.__cause__, ValidationError)


class PipelineIntegrationTest(unittest.TestCase):
    """GeminiReasoner plugs into run_investigation; ScriptedReasoner remains available for tests."""

    def test_fake_gemini_reasoner_runs_the_graph(self) -> None:
        row = {
            "event_id": "evt-1",
            "transaction_id": "TXN-1",
            "source": "PAYMENT_GATEWAY",
            "transaction_type": "PAYMENT",
            "amount_value": Decimal("10.00"),
            "amount_currency": "INR",
            "status": "SUCCESS",
            "canonical_status": "COMPLETED",
            "merchant_id": None,
        }
        client = FakeGeminiClient([FakeResponse(UNSURE_JSON)])
        reasoner = GeminiReasoner(client=client, model="test-gemini")

        def get_group(key, value, conn=None):
            return [row]

        def find_similar(text, k=5, *, embedder=None, conn=None):
            return []

        class SilentEmbedder:
            model = "test-embed"

            def embed(self, text: str) -> list[float]:
                return [1.0] + [0.0] * 767

        request = InvestigationRequest(case_id="pipe-1", source_phase="PHASE_3", transaction_ids=["TXN-1"])
        outcome = run_investigation(
            request,
            reasoner=reasoner,
            embedder=SilentEmbedder(),
            get_transaction_group=get_group,
            find_similar_cases=find_similar,
        )
        self.assertEqual(outcome.status, "LOW_CONFIDENCE")
        self.assertEqual(outcome.model, "test-gemini")
        self.assertEqual(outcome.attempts, 1)
        self.assertEqual(len(client.models.calls), 1)

    def test_scripted_reasoner_still_works(self) -> None:
        self.assertTrue(hasattr(ScriptedReasoner, "reason"))


@unittest.skipUnless(os.environ.get("RUN_GEMINI_TESTS") == "1", "set RUN_GEMINI_TESTS=1 to call the real Gemini API")
class RealGeminiReasonerTest(unittest.TestCase):
    """One live generate_content call. Off by default so the normal test run spends no quota."""

    def test_reason_returns_reasoning_output(self) -> None:
        load_dotenv(Path(__file__).resolve().parents[1] / ".env")
        reasoner = GeminiReasoner()
        output = reasoner.reason(sample_request(), sample_evidence())
        self.assertIsInstance(output, ReasoningOutput)
        self.assertIsInstance(output.confident, bool)
        self.assertIsInstance(output.cited_evidence, list)
        self.assertIsInstance(output.missing, list)


if __name__ == "__main__":
    unittest.main()
