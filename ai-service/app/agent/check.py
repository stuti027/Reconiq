"""The check step: plain code that decides whether to look again or finish.

Two rules live here, not in the model:
- Guard: an answer that claims confidence but cites nothing, or cites a label it was never shown, is treated as
  not confident.
- Loop: look again only if the answer is not confident, at least one requested fetch is new and runnable, and the
  reasoner has not yet been called MAX_REASONING_CALLS times. This is what makes the graph always stop.
"""

from collections.abc import Collection, Mapping
from dataclasses import dataclass
from typing import Literal

from app.agent.evidence import EvidenceRef, split_citations
from app.agent.reasoning import FetchRequest, ReasoningOutput

# Pass 1 plus one directed retry.
MAX_REASONING_CALLS = 2
# A retry runs at most this many of the requested fetches, in the order they were asked for.
MAX_FETCHES_PER_RETRY = 5

Decision = Literal["retry", "finalize"]


@dataclass(frozen=True)
class CheckResult:
    confident: bool
    overridden: bool
    next_fetches: tuple[FetchRequest, ...]
    decision: Decision


def is_runnable(fetch: FetchRequest) -> bool:
    """The transaction tool needs a lookup key; the similar-cases tool takes none."""
    if fetch.tool == "get_transaction_group":
        return fetch.key is not None
    return fetch.key is None


def check_reasoning(
    output: ReasoningOutput,
    label_map: Mapping[str, EvidenceRef],
    fetched: Collection[FetchRequest],
    attempts: int,
) -> CheckResult:
    """Apply the guard, then the loop condition.

    attempts is the number of reasoner calls made so far (1 after the first pass).
    next_fetches is empty unless the decision is "retry".
    """
    refs, unknown = split_citations(output.cited_evidence, label_map)
    confident = output.confident and bool(refs) and not unknown
    overridden = output.confident and not confident

    if confident or attempts >= MAX_REASONING_CALLS:
        return CheckResult(confident, overridden, (), "finalize")

    new: list[FetchRequest] = []
    for fetch in output.missing:
        if is_runnable(fetch) and fetch not in fetched and fetch not in new:
            new.append(fetch)
    new = new[:MAX_FETCHES_PER_RETRY]
    if not new:
        return CheckResult(confident, overridden, (), "finalize")
    return CheckResult(confident, overridden, tuple(new), "retry")
