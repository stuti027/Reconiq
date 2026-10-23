"""The investigation graph: gather evidence, reason, check, then look once more or finalize.

START -> gather_evidence -> reason -> check --finalize--> finalize -> END
                ^                       |
                +-------- retry --------+

Pass 1 is fixed: every request id by transaction_id, then similar past cases. A retry runs only the fetches the
check step accepted from the reasoner's `missing` list. The check step allows at most one retry, so the graph
always ends.

The tools and the embedder are passed in so tests can substitute fakes. The reasoner is always passed in;
this module does not call Gemini.
"""

from langgraph.graph import END, START, StateGraph

from app.agent.check import check_reasoning
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
    """Run the graph and return its outcome."""
    graph = _compile(reasoner, embedder, get_transaction_group, find_similar_cases)
    final = graph.invoke(
        {
            "request": request,
            "evidence": [],
            "fetched": set(),
            "attempts": 0,
            "last_reasoning": None,
            "confident": False,
            "pending": [],
            "outcome": None,
        }
    )
    return final["outcome"]


def _compile(reasoner, embedder, get_transaction_group, find_similar_cases):
    def first_pass(state: InvestigationState) -> dict:
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

    def directed_pass(state: InvestigationState) -> dict:
        new_items: list[EvidenceItem] = []
        for fetch in state["pending"]:
            if fetch.tool == "get_transaction_group":
                for row in get_transaction_group(fetch.key, fetch.value):
                    new_items.append(EvidenceItem.from_row(fetch, row))
            else:
                for case in find_similar_cases(fetch.value, embedder=embedder):
                    new_items.append(EvidenceItem.from_past_case(fetch, case))
        return {"evidence": new_items, "fetched": set(state["pending"]), "pending": []}

    def gather_evidence(state: InvestigationState) -> dict:
        if state["attempts"] == 0:
            return first_pass(state)
        return directed_pass(state)

    def reason(state: InvestigationState) -> dict:
        labelled, _label_map = label_evidence(state["evidence"])
        output = reasoner.reason(state["request"], labelled)
        return {"last_reasoning": output, "attempts": state["attempts"] + 1}

    def check(state: InvestigationState) -> dict:
        _labelled, label_map = label_evidence(state["evidence"])
        result = check_reasoning(state["last_reasoning"], label_map, state["fetched"], state["attempts"])
        return {"confident": result.confident, "pending": list(result.next_fetches)}

    def route_after_check(state: InvestigationState) -> str:
        return "retry" if state["pending"] else "finalize"

    def finalize(state: InvestigationState) -> dict:
        output = state["last_reasoning"]
        labelled, label_map = label_evidence(state["evidence"])
        cited, _unknown = split_citations(output.cited_evidence, label_map)
        status = "RESOLVED" if state["confident"] else "LOW_CONFIDENCE"
        if status == "RESOLVED":
            # Labelled evidence holds each row once; a retry can return rows pass 1 already had.
            rows = [e.item.data for e in labelled if e.item.ref.kind == "transaction"]
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
    builder.add_node("check", check)
    builder.add_node("finalize", finalize)
    builder.add_edge(START, "gather_evidence")
    builder.add_edge("gather_evidence", "reason")
    builder.add_edge("reason", "check")
    builder.add_conditional_edges("check", route_after_check, {"retry": "gather_evidence", "finalize": "finalize"})
    builder.add_edge("finalize", END)
    return builder.compile()
