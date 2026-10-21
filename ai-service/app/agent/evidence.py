"""Evidence items and their labels.

The model never sees raw ids as citation targets. Each distinct piece of evidence is shown as E1, E2, ... and
the model cites those labels. Code keeps the label -> real id map, so "cited something it was never shown"
is a set lookup, and the final result can name real rows and cases.
"""

from dataclasses import dataclass
from typing import Any, Literal, Sequence

from app.agent.reasoning import FetchRequest

EvidenceKind = Literal["transaction", "past_case"]


@dataclass(frozen=True)
class EvidenceRef:
    """What a label points at: a canonical row (by event_id) or a past case (by case_id)."""

    kind: EvidenceKind
    ref_id: str


@dataclass(frozen=True)
class EvidenceItem:
    """One piece of evidence and the fetch that produced it."""

    fetch: FetchRequest
    ref: EvidenceRef
    data: dict[str, Any]

    @classmethod
    def from_row(cls, fetch: FetchRequest, row: dict[str, Any]) -> "EvidenceItem":
        """A canonical row from get_transaction_group."""
        return cls(fetch=fetch, ref=EvidenceRef("transaction", row["event_id"]), data=row)

    @classmethod
    def from_past_case(cls, fetch: FetchRequest, case: dict[str, Any]) -> "EvidenceItem":
        """A past case from find_similar_cases."""
        return cls(fetch=fetch, ref=EvidenceRef("past_case", case["case_id"]), data=case)


@dataclass(frozen=True)
class LabelledEvidence:
    label: str
    item: EvidenceItem


def label_evidence(items: Sequence[EvidenceItem]) -> tuple[list[LabelledEvidence], dict[str, EvidenceRef]]:
    """Number evidence E1, E2, ... in order of first appearance.

    An item that points at something already labelled (the same row returned by a second lookup) is left out,
    so each row or case appears once. Because evidence is only ever appended, items labelled on pass 1 keep
    their labels after pass 2 adds more.

    Returns the labelled items, in order, and the label -> EvidenceRef map.
    """
    labelled: list[LabelledEvidence] = []
    label_map: dict[str, EvidenceRef] = {}
    seen: set[EvidenceRef] = set()
    for item in items:
        if item.ref in seen:
            continue
        seen.add(item.ref)
        label = f"E{len(labelled) + 1}"
        labelled.append(LabelledEvidence(label, item))
        label_map[label] = item.ref
    return labelled, label_map


def split_citations(
    cited: Sequence[str], label_map: dict[str, EvidenceRef]
) -> tuple[list[EvidenceRef], list[str]]:
    """Map cited labels back to real refs.

    Returns (refs for known labels, in citation order without repeats; labels that were never shown).
    """
    refs: list[EvidenceRef] = []
    unknown: list[str] = []
    for label in cited:
        ref = label_map.get(label)
        if ref is None:
            if label not in unknown:
                unknown.append(label)
        elif ref not in refs:
            refs.append(ref)
    return refs, unknown
