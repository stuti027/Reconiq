"""What Phase 4 sends to Phase 5 when an investigation ends, for every outcome.

One shape carries all three statuses. Evidence goes out as references (event_id / case_id), never as copied rows,
so no amount travels as a JSON number; Phase 5 reads the rows from Postgres if it needs them. Phase 4 explains
why a mismatch happened and Phase 5 decides what to do about it, so there is no severity or recommended action.
"""

from datetime import datetime, timedelta, timezone
from typing import Literal, Sequence

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator

from app.agent.check import MAX_REASONING_CALLS
from app.agent.evidence import EvidenceItem, EvidenceKind, EvidenceRef
from app.agent.state import InvestigationOutcome
from app.contracts import InvestigationRequest

ResultStatus = Literal["RESOLVED", "LOW_CONFIDENCE", "FAILED"]
SCHEMA_VERSION = 1


class EvidenceReference(BaseModel):
    """A row (`transaction`, by event_id) or a past case (`past_case`, by case_id) the investigation used."""

    model_config = ConfigDict(extra="forbid", frozen=True)

    kind: EvidenceKind
    ref_id: str = Field(min_length=1)

    @classmethod
    def from_ref(cls, ref: EvidenceRef) -> "EvidenceReference":
        return cls(kind=ref.kind, ref_id=ref.ref_id)


class InvestigationResult(BaseModel):
    """One finished investigation, as sent to Phase 5. Exactly one per case, whatever the outcome.

    RESOLVED needs a root cause and at least one cited reference. LOW_CONFIDENCE may carry a tentative root
    cause. FAILED carries a failure_reason code and no root cause. Every cited reference is also in
    examined_evidence. investigated_at is UTC and serialises as ISO-8601 with a Z suffix.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: Literal[1] = SCHEMA_VERSION
    case_id: str = Field(min_length=1)
    source_phase: Literal["PHASE_3", "PHASE_6"]
    transaction_ids: list[str] = Field(min_length=1)
    status: ResultStatus
    root_cause: str | None
    cited_evidence: list[EvidenceReference]
    examined_evidence: list[EvidenceReference]
    failure_reason: str | None = Field(pattern=r"^[A-Z][A-Z0-9_]*$")
    attempts: int = Field(ge=0, le=MAX_REASONING_CALLS)
    model: str = Field(min_length=1)
    investigated_at: AwareDatetime

    @field_validator("investigated_at")
    @classmethod
    def _in_utc(cls, value: datetime) -> datetime:
        if value.utcoffset() != timedelta(0):
            raise ValueError("investigated_at must be in UTC")
        return value

    @model_validator(mode="after")
    def _status_rules(self) -> "InvestigationResult":
        if self.status == "FAILED":
            if self.failure_reason is None:
                raise ValueError("a FAILED result needs a failure_reason")
            if self.root_cause is not None:
                raise ValueError("a FAILED result has no root_cause")
        else:
            if self.failure_reason is not None:
                raise ValueError(f"a {self.status} result has no failure_reason")
            if self.attempts < 1:
                raise ValueError(f"a {self.status} result comes from at least one reasoning call")
        if self.status == "RESOLVED":
            if not (self.root_cause and self.root_cause.strip()):
                raise ValueError("a RESOLVED result needs a root_cause")
            if not self.cited_evidence:
                raise ValueError("a RESOLVED result cites at least one reference")
        not_examined = set(self.cited_evidence) - set(self.examined_evidence)
        if not_examined:
            raise ValueError(f"cited references missing from examined_evidence: {sorted(r.ref_id for r in not_examined)}")
        return self


def result_from_outcome(
    request: InvestigationRequest,
    outcome: InvestigationOutcome,
    *,
    investigated_at: datetime | None = None,
) -> InvestigationResult:
    """The result for an investigation the graph finished (RESOLVED or LOW_CONFIDENCE)."""
    return InvestigationResult(
        case_id=request.case_id,
        source_phase=request.source_phase,
        transaction_ids=list(request.transaction_ids),
        status=outcome.status,
        root_cause=outcome.root_cause,
        cited_evidence=[EvidenceReference.from_ref(ref) for ref in outcome.cited_references],
        examined_evidence=_distinct_references(outcome.evidence),
        failure_reason=None,
        attempts=outcome.attempts,
        model=outcome.model,
        investigated_at=investigated_at or _utc_now(),
    )


def failed_result(
    request: InvestigationRequest,
    *,
    failure_reason: str,
    model: str,
    attempts: int = 0,
    evidence: Sequence[EvidenceItem] = (),
    investigated_at: datetime | None = None,
) -> InvestigationResult:
    """The result for an investigation that could not finish. Evidence gathered before the failure is listed."""
    return InvestigationResult(
        case_id=request.case_id,
        source_phase=request.source_phase,
        transaction_ids=list(request.transaction_ids),
        status="FAILED",
        root_cause=None,
        cited_evidence=[],
        examined_evidence=_distinct_references(evidence),
        failure_reason=failure_reason,
        attempts=attempts,
        model=model,
        investigated_at=investigated_at or _utc_now(),
    )


def _distinct_references(evidence: Sequence[EvidenceItem]) -> list[EvidenceReference]:
    """Each row or case once, in the order it was first found (a retry can return a row pass 1 already had)."""
    seen: dict[EvidenceRef, None] = {}
    for item in evidence:
        seen.setdefault(item.ref, None)
    return [EvidenceReference.from_ref(ref) for ref in seen]


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)
