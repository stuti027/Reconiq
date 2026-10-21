"""What the reasoning step may return, and the interface every reasoner implements.

The model answers with one ReasoningOutput. It can ask for more evidence through `missing`, but it never runs a
tool itself: the graph decides whether a requested fetch is run.
"""

from typing import TYPE_CHECKING, Literal, Protocol, Sequence

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.contracts import InvestigationRequest
from app.tools.transactions import LookupKey

if TYPE_CHECKING:
    from app.agent.evidence import LabelledEvidence

ToolName = Literal["get_transaction_group", "find_similar_cases"]


class FetchRequest(BaseModel):
    """One tool call: which tool, which lookup key (transaction tool only) and the value to look up.

    Frozen, so it can sit in a set of fetches already run. Whether the key fits the tool is checked by the
    graph before running it, not here, so one odd request does not throw away the rest of the answer.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    tool: ToolName
    key: LookupKey | None
    value: str = Field(min_length=1)


class ReasoningOutput(BaseModel):
    """One answer from the reasoning step.

    cited_evidence holds evidence labels ("E1", "E3"), never raw ids; code maps them back.
    missing is empty when no further fetch would help.
    """

    model_config = ConfigDict(extra="forbid")

    confident: bool
    root_cause: str | None
    cited_evidence: list[str]
    missing: list[FetchRequest]

    @model_validator(mode="after")
    def _root_cause_when_confident(self) -> "ReasoningOutput":
        if self.confident and not (self.root_cause and self.root_cause.strip()):
            raise ValueError("root_cause is required when confident is true")
        return self


class ReasonerError(Exception):
    """Base class for reasoning-step failures that are about this case, not the environment.

    Each subclass carries the reason code a FAILED result reports.
    """

    reason_code: str


class LLMOutputInvalidError(ReasonerError):
    """The model's answer failed validation twice in a row."""

    reason_code = "LLM_OUTPUT_INVALID"


class LLMRefusedError(ReasonerError):
    """The model refused or blocked the input."""

    reason_code = "LLM_REFUSED"


class Reasoner(Protocol):
    """Reads a request and its labelled evidence, returns one ReasoningOutput.

    Raises LLMOutputInvalidError or LLMRefusedError for case failures; network and quota errors are raised
    unchanged. `model` names the model so results can record which one produced them.
    """

    model: str

    def reason(self, request: InvestigationRequest, evidence: Sequence["LabelledEvidence"]) -> ReasoningOutput: ...
