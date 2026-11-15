from typing import Any, Literal

from pydantic import BaseModel, Field


class InvestigationRequest(BaseModel):
    """Shared intake body for Phase 3 (one id) and Phase 6 (many ids)."""

    case_id: str
    source_phase: Literal["PHASE_3", "PHASE_6"]
    transaction_ids: list[str] = Field(min_length=1)
    context: dict[str, Any] = Field(default_factory=dict)


class InvestigateAck(BaseModel):
    case_id: str
    status: Literal["accepted", "already_received"]
