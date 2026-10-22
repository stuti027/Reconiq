"""One pass of the investigation graph: gather evidence, reason, finalize.

START -> gather_evidence -> reason -> finalize -> END

The tools and the embedder are passed in so tests can substitute fakes. The reasoner is always passed in;
this module does not call Gemini.
"""

from langgraph.graph import END, START, StateGraph

from app.agent.evidence import EvidenceItem, label_evidence, split_citations
from app.agent.reasoning import FetchRequest, Reasoner
from app.agent.state import InvestigationOutcome, InvestigationState
from app.contracts import InvestigationRequest
from app.tools.similar_cases import (
    Embedder,
    describe_case,
    find_similar_cases as default_find_similar_cases,
    save_resolved_case,
)
from app.tools.transactions import get_transaction_group as default_get_transaction_group


def run_investigation(
    request: InvestigationRequest,
    *,
    reasoner: Reasoner,
    embedder: Embedder | None = None,
    get_transaction_group=default_get_transaction_group,
    find_similar_cases=default_find_similar_cases,
) -> InvestigationOutcome:
    """Run the single-pass graph and return its outcome."""
    graph = _compile(reasoner, embedder, get_transaction_group, find_similar_cases)
    final = graph.invoke(
        {
            "request": request,
            "evidence": [],
            "fetched": set(),
            "attempts": 0,
            "last_reasoning": None,
            "outcome": None,
        }
    )
    return final["outcome"]


def _compile(reasoner, embedder, get_transaction_group, find_similar_cases):
    def gather_evidence(state: InvestigationState) -> dict:
        new_items: list[EvidenceItem] = []
        new_fetches: set[FetchRequest] = set()
        rows: list[dict] = []
        for txn_id in state["request"].transaction_ids:
            fetch = FetchRequest(tool="get_transaction_group", key="transaction_id", value=txn_id)
            found = get_transaction_group("transaction_id", txn_id)
            new_fetches.add(fetch)
            for row in found:
                rows.append(row)
                new_items.append(EvidenceItem.from_row(fetch, row))

        description = describe_case(rows)
        similar = FetchRequest(tool="find_similar_cases", key=None, value=description)
        found_cases = find_similar_cases(description, embedder=embedder)
        new_fetches.add(similar)
        for case in found_cases:
            new_items.append(EvidenceItem.from_past_case(similar, case))
        return {"evidence": new_items, "fetched": new_fetches}

    def reason(state: InvestigationState) -> dict:
        labelled, _label_map = label_evidence(state["evidence"])
        output = reasoner.reason(state["request"], labelled)
        return {"last_reasoning": output, "attempts": state["attempts"] + 1}

    def finalize(state: InvestigationState) -> dict:
        output = state["last_reasoning"]
        _labelled, label_map = label_evidence(state["evidence"])
        cited, _unknown = split_citations(output.cited_evidence, label_map)
        status = "RESOLVED" if output.confident else "LOW_CONFIDENCE"
        if status == "RESOLVED":
            rows = [item.data for item in state["evidence"] if item.ref.kind == "transaction"]
            save_resolved_case(
                state["request"].case_id,
                status,
                list(state["request"].transaction_ids),
                describe_case(rows),
                output.root_cause or "",
                embedder=embedder,
            )
        outcome = InvestigationOutcome(
            status=status,
            root_cause=output.root_cause,
            cited_references=cited,
            attempts=state["attempts"],
            evidence=list(state["evidence"]),
            model=reasoner.model,
        )
        return {"outcome": outcome}

    builder = StateGraph(InvestigationState)
    builder.add_node("gather_evidence", gather_evidence)
    builder.add_node("reason", reason)
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "gather_evidence")
    builder.add_edge("gather_evidence", "reason")
    builder.add_edge("reason", "finalize")
    builder.add_edge("finalize", END)
    return builder.compile()
