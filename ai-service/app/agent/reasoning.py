"""What the reasoning step may return, and the interface every reasoner implements.

The model answers with one ReasoningOutput. It can ask for more evidence through `missing`, but it never runs a
tool itself: the graph decides whether a requested fetch is run.
"""

import os
from typing import TYPE_CHECKING, Literal, Protocol, Sequence

from google import genai
from google.genai import types
from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

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


# Finish reasons that mean the model (or the safety layer) refused to answer. Not ordinary invalid JSON.
_REFUSAL_FINISH_REASONS = frozenset(
    {
        "SAFETY",
        "RECITATION",
        "BLOCKLIST",
        "PROHIBITED_CONTENT",
        "SPII",
        "IMAGE_SAFETY",
        "IMAGE_PROHIBITED_CONTENT",
    }
)
_UNSPECIFIED_BLOCK = frozenset({"", "BLOCKED_REASON_UNSPECIFIED", "NONE"})


class GeminiReasoner:
    """Calls Gemini once per graph reasoning step. Invalid JSON is retried once; refusals are not."""

    def __init__(self, client: object | None = None, model: str | None = None) -> None:
        self._client = client or genai.Client(api_key=os.environ["GEMINI_API_KEY"])
        self.model = model if model is not None else os.environ["GEMINI_MODEL"]

    def reason(self, request: InvestigationRequest, evidence: Sequence["LabelledEvidence"]) -> ReasoningOutput:
        # Imported here so this module can be loaded without importing evidence (which imports FetchRequest).
        from app.agent.prompt import build_prompt

        prompt = build_prompt(request, evidence)
        last_invalid: Exception | None = None
        for _attempt in range(2):
            response = self._generate(prompt)
            self._raise_if_refused(response)
            try:
                return self._parse(response)
            except (ValidationError, ValueError, TypeError) as exc:
                last_invalid = exc
        raise LLMOutputInvalidError("Gemini returned invalid output twice") from last_invalid

    def _generate(self, prompt: str) -> object:
        config_kwargs: dict = {
            "temperature": 0,
            "response_mime_type": "application/json",
        }
        try:
            config = types.GenerateContentConfig(
                **config_kwargs,
                response_schema=ReasoningOutput,
            )
        except (TypeError, ValueError, ValidationError):
            config = types.GenerateContentConfig(**config_kwargs)
        return self._client.models.generate_content(
            model=self.model,
            contents=prompt,
            config=config,
        )

    def _parse(self, response: object) -> ReasoningOutput:
        text = getattr(response, "text", None)
        if text is None or not str(text).strip():
            raise ValueError("Gemini returned an empty response")
        return ReasoningOutput.model_validate_json(str(text).strip())

    def _raise_if_refused(self, response: object) -> None:
        feedback = getattr(response, "prompt_feedback", None)
        block_reason = getattr(feedback, "block_reason", None) if feedback is not None else None
        if _token(block_reason) not in _UNSPECIFIED_BLOCK:
            raise LLMRefusedError(f"Gemini blocked the prompt: {block_reason}")

        candidates = getattr(response, "candidates", None) or ()
        if candidates:
            finish = getattr(candidates[0], "finish_reason", None)
            if _token(finish) in _REFUSAL_FINISH_REASONS:
                raise LLMRefusedError(f"Gemini refused to answer: {finish}")
            return

        if feedback is not None:
            raise LLMRefusedError("Gemini returned no candidates")


def _token(value: object) -> str:
    if value is None:
        return ""
    raw = getattr(value, "value", value)
    return str(raw).upper()
