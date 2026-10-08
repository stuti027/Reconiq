"""Memory tool: find past cases that read like the current one, and remember resolved ones.

Past cases live in investigation.past_cases (owned by Phase 4). Each row stores a short text description of
a case, its root cause, and an embedding of that text. Search ranks rows by cosine distance between
embeddings, so "similar" means similar in meaning, not equal in any column.
"""

import os
from collections import defaultdict
from decimal import Decimal
from typing import Any, Protocol

import psycopg
from google import genai
from google.genai import types
from pgvector import Vector
from pgvector.psycopg import register_vector
from psycopg.rows import dict_row
from psycopg.types.json import Json

from app.main import connect

# Fixed by the column type below. A different size needs a new column, not a setting change.
EMBEDDING_DIMENSIONS = 768

SCHEMA_SQL = f"""
CREATE SCHEMA IF NOT EXISTS investigation;

CREATE TABLE IF NOT EXISTS investigation.past_cases (
    case_id text PRIMARY KEY,
    transaction_ids jsonb NOT NULL,
    description_text text NOT NULL,
    root_cause text NOT NULL,
    embedding vector({EMBEDDING_DIMENSIONS}) NOT NULL,
    embedding_model text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
"""

_table_ready = False


class Embedder(Protocol):
    """Turns text into a vector. `model` names the model, since vectors from different models can't be compared."""

    model: str

    def embed(self, text: str) -> list[float]: ...


class GeminiEmbedder:
    """Embeds text with the Gemini embedding model named in GEMINI_EMBEDDING_MODEL."""

    def __init__(self) -> None:
        self.model = os.environ["GEMINI_EMBEDDING_MODEL"]
        self._client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])

    def embed(self, text: str) -> list[float]:
        response = self._client.models.embed_content(
            model=self.model,
            contents=text,
            config=types.EmbedContentConfig(
                # Query and stored text are the same kind of thing (case descriptions), so the symmetric task type.
                task_type="SEMANTIC_SIMILARITY",
                output_dimensionality=EMBEDDING_DIMENSIONS,
            ),
        )
        values = response.embeddings[0].values
        if len(values) != EMBEDDING_DIMENSIONS:
            raise ValueError(f"{self.model} returned {len(values)} dimensions, expected {EMBEDDING_DIMENSIONS}")
        return values


def describe_case(rows: list[dict[str, Any]]) -> str:
    """Build the text that gets embedded, from a transaction group (get_transaction_group's output).

    Ids are left out on purpose: two cases with the same situation should read the same even though their ids
    differ. Sources are listed in a fixed order so the same rows always give the same text.
    """
    if not rows:
        return "No canonical rows found for this case."

    by_source: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_source[row["source"]].append(row)
    sources = sorted(by_source)
    currencies = {row["amount_currency"] for row in rows}

    parts = [" vs ".join(sources) if len(sources) > 1 else f"only {sources[0]} rows, no counterpart"]
    merchants = sorted({row["merchant_id"] for row in rows if row["merchant_id"]})
    if merchants:
        parts.append("merchant " + ", ".join(merchants))

    totals: dict[str, Decimal] = {}
    for source in sources:
        source_rows = by_source[source]
        amounts = [row["amount_value"] for row in source_rows if row["amount_value"] is not None]
        totals[source] = sum(amounts, Decimal("0"))
        statuses = ", ".join(sorted({f"{row['status']}/{row['canonical_status']}" for row in source_rows}))
        types_ = ", ".join(sorted({row["transaction_type"] for row in source_rows if row["transaction_type"]}))
        count = f"{len(source_rows)} rows" if len(source_rows) > 1 else "1 row"
        currency = "/".join(sorted({row["amount_currency"] or "?" for row in source_rows}))
        parts.append(f"{source}: {count}, total {totals[source]} {currency}, type {types_ or 'unknown'}, status {statuses}")

    if len(sources) > 1:
        if len(currencies) == 1 and None not in currencies:
            gap = max(totals.values()) - min(totals.values())
            parts.append(f"amount gap {gap} {next(iter(currencies))}")
        else:
            parts.append("mixed or missing currencies, no amount gap computed")
    return "; ".join(parts)


def find_similar_cases(
    description_text: str,
    k: int = 5,
    *,
    embedder: Embedder | None = None,
    conn: psycopg.Connection | None = None,
) -> list[dict[str, Any]]:
    """Return up to k past cases nearest in meaning to description_text, nearest first.

    Each result has case_id, transaction_ids, description_text, root_cause and distance (cosine distance:
    0 = same meaning, larger = less alike). Only cases embedded with the same model are compared.
    An empty list means no past case exists yet. It is not an error.
    Database and Gemini errors are raised unchanged, for the failure path to classify.
    """
    if not description_text.strip():
        raise ValueError("description_text must not be empty")
    if k < 1:
        raise ValueError(f"k must be at least 1, got {k}")
    embedder = embedder or GeminiEmbedder()
    query_vector = Vector(embedder.embed(description_text))

    if conn is not None:
        return _search(conn, query_vector, embedder.model, k)
    with connect() as own_conn:
        return _search(own_conn, query_vector, embedder.model, k)


def save_resolved_case(
    case_id: str,
    status: str,
    transaction_ids: list[str],
    description_text: str,
    root_cause: str,
    *,
    embedder: Embedder | None = None,
    conn: psycopg.Connection | None = None,
) -> bool:
    """Remember a finished case for future searches. Returns True only if a new row was written.

    Only RESOLVED cases are kept; any other status returns False without writing or embedding, so the caller
    can pass every finished case. A case_id already stored is never written twice (a re-run after a crash),
    and is checked before embedding so a re-run spends no Gemini call.
    The stored embedding is of the description plus its root cause.
    With conn given, the caller owns the transaction (and must commit); otherwise this commits.
    """
    if status != "RESOLVED":
        return False
    if not description_text.strip() or not root_cause.strip():
        raise ValueError("description_text and root_cause must not be empty")

    if conn is not None:
        return _save(conn, case_id, transaction_ids, description_text, root_cause, embedder)
    with connect() as own_conn:
        return _save(own_conn, case_id, transaction_ids, description_text, root_cause, embedder)


def ensure_past_cases_table() -> None:
    """Create the investigation schema and past_cases table if missing. Runs the DDL once per process.

    Uses its own connection, so it never commits a transaction a caller has open.
    """
    global _table_ready
    if _table_ready:
        return
    with connect() as conn:
        conn.execute(SCHEMA_SQL)
    _table_ready = True


def _prepare(conn: psycopg.Connection) -> None:
    ensure_past_cases_table()
    register_vector(conn)


def _search(conn: psycopg.Connection, query_vector: Vector, model: str, k: int) -> list[dict[str, Any]]:
    _prepare(conn)
    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute(
            """
            SELECT case_id, transaction_ids, description_text, root_cause, embedding <=> %s AS distance
            FROM investigation.past_cases
            WHERE embedding_model = %s
            ORDER BY distance, case_id
            LIMIT %s
            """,
            (query_vector, model, k),
        )
        return cur.fetchall()


def _save(
    conn: psycopg.Connection,
    case_id: str,
    transaction_ids: list[str],
    description_text: str,
    root_cause: str,
    embedder: Embedder | None,
) -> bool:
    _prepare(conn)
    if conn.execute("SELECT 1 FROM investigation.past_cases WHERE case_id = %s", (case_id,)).fetchone():
        return False
    embedder = embedder or GeminiEmbedder()
    vector = Vector(embedder.embed(f"{description_text}\nRoot cause: {root_cause}"))
    cur = conn.execute(
        """
        INSERT INTO investigation.past_cases
            (case_id, transaction_ids, description_text, root_cause, embedding, embedding_model)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON CONFLICT (case_id) DO NOTHING
        """,
        (case_id, Json(transaction_ids), description_text, root_cause, vector, embedder.model),
    )
    return cur.rowcount == 1
