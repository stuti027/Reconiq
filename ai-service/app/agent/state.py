"""State carried through one investigation, and the plain outcome at the end.

Evidence is appended. `fetched` records tool calls already made so a later pass can skip them.
`attempts` starts at 0 and goes up by one each time the reasoner is called.
`confident` is the check step's verdict (after the guard), not the model's own claim.
`pending` holds the fetches the next pass will run; it is empty unless the check step chose to retry.
"""

from operator import or_
from typing import Annotated, TypedDict

from app.agent.evidence import EvidenceItem, EvidenceRef
from app.agent.reasoning import FetchRequest, ReasoningOutput
from app.contracts import InvestigationRequest


def _append(left: list[EvidenceItem], right: list[EvidenceItem]) -> list[EvidenceItem]:
    return [*left, *right]


class InvestigationOutcome:
    """The result of one investigation run. Cited references are real ids, not E1/E2 labels."""

    def __init__(
        self,
        status: str,
        root_cause: str | None,
        cited_references: list[EvidenceRef],
        attempts: int,
        evidence: list[EvidenceItem],
        model: str,
    ) -> None:
        self.status = status
        self.root_cause = root_cause
        self.cited_references = cited_references
        self.attempts = attempts
        self.evidence = evidence
        self.model = model


class InvestigationState(TypedDict):
    request: InvestigationRequest
    evidence: Annotated[list[EvidenceItem], _append]
    fetched: Annotated[set[FetchRequest], or_]
    attempts: int
    last_reasoning: ReasoningOutput | None
    confident: bool
    pending: list[FetchRequest]
    outcome: InvestigationOutcome | None
