"""A scripted stand-in for the real reasoner, for tests.

It returns preset answers in order (or raises preset errors), so every graph path can be driven exactly,
without network calls or cost.
"""

from typing import Iterable, Sequence

from app.agent.evidence import LabelledEvidence
from app.agent.reasoning import ReasoningOutput
from app.contracts import InvestigationRequest


class ScriptedReasoner:
    """Replays a script of ReasoningOutput objects or exceptions, one per call, and records every call."""

    def __init__(self, script: Iterable[ReasoningOutput | Exception], model: str = "scripted-test-reasoner") -> None:
        self.model = model
        self._script = list(script)
        self.calls: list[tuple[InvestigationRequest, list[LabelledEvidence]]] = []

    def reason(self, request: InvestigationRequest, evidence: Sequence[LabelledEvidence]) -> ReasoningOutput:
        self.calls.append((request, list(evidence)))
        if not self._script:
            raise AssertionError(f"ScriptedReasoner ran out of answers on call {len(self.calls)}")
        step = self._script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step
