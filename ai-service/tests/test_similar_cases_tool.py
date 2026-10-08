"""Tests for the memory tool, against the sandbox database.

Most tests use FakeEmbedder (fixed vectors, no network) so they are fast, free and exact. Its model name is
unique per test, and search only compares vectors from the same model, so other rows in the table never
affect these results. One test class calls the real Gemini embedding API.
"""

import math
import os
import unittest
import uuid
from decimal import Decimal

from app.main import connect
from app.tools.similar_cases import (
    EMBEDDING_DIMENSIONS,
    describe_case,
    find_similar_cases,
    save_resolved_case,
)
from app.tools.transactions import get_transaction_group


def unit_vector(*weights: float) -> list[float]:
    """A 768-long vector with the given leading values, scaled to length 1."""
    values = list(weights) + [0.0] * (EMBEDDING_DIMENSIONS - len(weights))
    norm = math.sqrt(sum(v * v for v in values))
    return [v / norm for v in values]


class FakeEmbedder:
    """Returns a preset vector per text and counts calls."""

    def __init__(self, vectors: dict[str, list[float]]) -> None:
        self.model = "test-fake-" + uuid.uuid4().hex
        self.vectors = vectors
        self.calls: list[str] = []

    def embed(self, text: str) -> list[float]:
        self.calls.append(text)
        return self.vectors[text]


def stored_text(description: str, root_cause: str) -> str:
    return f"{description}\nRoot cause: {root_cause}"


class PastCasesTestBase(unittest.TestCase):
    def setUp(self) -> None:
        self.created: list[str] = []

    def tearDown(self) -> None:
        if self.created:
            with connect() as conn:
                conn.execute("DELETE FROM investigation.past_cases WHERE case_id = ANY(%s)", (self.created,))

    def new_case_id(self) -> str:
        cid = "subphase44-" + uuid.uuid4().hex
        self.created.append(cid)
        return cid


class SaveAndFindTest(PastCasesTestBase):
    def test_empty_store_returns_empty_list(self) -> None:
        embedder = FakeEmbedder({"anything": unit_vector(1)})
        self.assertEqual(find_similar_cases("anything", embedder=embedder), [])

    def test_saved_case_comes_back_from_search(self) -> None:
        cid = self.new_case_id()
        embedder = FakeEmbedder(
            {
                stored_text("fee gap", "gateway fee deducted"): unit_vector(1, 0),
                "fee gap again": unit_vector(1, 0),
            }
        )
        self.assertTrue(
            save_resolved_case(cid, "RESOLVED", ["TXN-FEE-001"], "fee gap", "gateway fee deducted", embedder=embedder)
        )
        results = find_similar_cases("fee gap again", embedder=embedder)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["case_id"], cid)
        self.assertEqual(results[0]["transaction_ids"], ["TXN-FEE-001"])
        self.assertEqual(results[0]["description_text"], "fee gap")
        self.assertEqual(results[0]["root_cause"], "gateway fee deducted")
        self.assertAlmostEqual(results[0]["distance"], 0.0, places=5)

    def test_results_are_nearest_first_and_limited_to_k(self) -> None:
        near, middle, far = self.new_case_id(), self.new_case_id(), self.new_case_id()
        embedder = FakeEmbedder(
            {
                stored_text("near", "r"): unit_vector(1, 0.1),
                stored_text("middle", "r"): unit_vector(1, 1),
                stored_text("far", "r"): unit_vector(0, 1),
                "query": unit_vector(1, 0),
            }
        )
        for cid, text in [(far, "far"), (near, "near"), (middle, "middle")]:
            save_resolved_case(cid, "RESOLVED", ["T"], text, "r", embedder=embedder)
        results = find_similar_cases("query", k=2, embedder=embedder)
        self.assertEqual([r["case_id"] for r in results], [near, middle])
        self.assertLess(results[0]["distance"], results[1]["distance"])

    def test_vectors_from_another_model_are_not_compared(self) -> None:
        cid = self.new_case_id()
        writer = FakeEmbedder({stored_text("x", "r"): unit_vector(1)})
        reader = FakeEmbedder({"x": unit_vector(1)})
        save_resolved_case(cid, "RESOLVED", ["T"], "x", "r", embedder=writer)
        self.assertEqual(find_similar_cases("x", embedder=reader), [])

    def test_embedding_is_of_description_plus_root_cause(self) -> None:
        cid = self.new_case_id()
        embedder = FakeEmbedder({stored_text("desc", "cause"): unit_vector(1)})
        save_resolved_case(cid, "RESOLVED", ["T"], "desc", "cause", embedder=embedder)
        self.assertEqual(embedder.calls, ["desc\nRoot cause: cause"])


class WriteRulesTest(PastCasesTestBase):
    def test_only_resolved_cases_are_written(self) -> None:
        embedder = FakeEmbedder({})
        for status in ["LOW_CONFIDENCE", "FAILED", "resolved", ""]:
            with self.subTest(status=status):
                cid = self.new_case_id()
                self.assertFalse(save_resolved_case(cid, status, ["T"], "d", "r", embedder=embedder))
                with connect() as conn:
                    row = conn.execute("SELECT 1 FROM investigation.past_cases WHERE case_id = %s", (cid,)).fetchone()
                self.assertIsNone(row)
        self.assertEqual(embedder.calls, [])

    def test_same_case_saved_twice_is_stored_once_and_embedded_once(self) -> None:
        cid = self.new_case_id()
        embedder = FakeEmbedder({stored_text("d", "r"): unit_vector(1), stored_text("d2", "r2"): unit_vector(0, 1)})
        self.assertTrue(save_resolved_case(cid, "RESOLVED", ["T"], "d", "r", embedder=embedder))
        self.assertFalse(save_resolved_case(cid, "RESOLVED", ["T"], "d2", "r2", embedder=embedder))
        self.assertEqual(len(embedder.calls), 1)
        with connect() as conn:
            rows = conn.execute(
                "SELECT description_text FROM investigation.past_cases WHERE case_id = %s", (cid,)
            ).fetchall()
        self.assertEqual(rows, [("d",)])

    def test_given_connection_leaves_commit_to_the_caller(self) -> None:
        cid = self.new_case_id()
        embedder = FakeEmbedder({stored_text("d", "r"): unit_vector(1)})
        with connect() as conn:
            self.assertTrue(save_resolved_case(cid, "RESOLVED", ["T"], "d", "r", embedder=embedder, conn=conn))
            conn.rollback()
        with connect() as conn:
            row = conn.execute("SELECT 1 FROM investigation.past_cases WHERE case_id = %s", (cid,)).fetchone()
        self.assertIsNone(row)

    def test_bad_arguments_are_refused(self) -> None:
        embedder = FakeEmbedder({})
        with self.assertRaises(ValueError):
            find_similar_cases("   ", embedder=embedder)
        with self.assertRaises(ValueError):
            find_similar_cases("text", k=0, embedder=embedder)
        with self.assertRaises(ValueError):
            save_resolved_case(self.new_case_id(), "RESOLVED", ["T"], "d", "  ", embedder=embedder)

    def test_wrong_vector_size_is_rejected_by_the_column(self) -> None:
        import psycopg

        embedder = FakeEmbedder({stored_text("d", "r"): [1.0, 0.0, 0.0]})
        with self.assertRaises(psycopg.errors.DataException):
            save_resolved_case(self.new_case_id(), "RESOLVED", ["T"], "d", "r", embedder=embedder)


class DescribeCaseTest(unittest.TestCase):
    def test_fee_case(self) -> None:
        text = describe_case(get_transaction_group("settlement_id", "SET-FEE-001"))
        self.assertTrue(text.startswith("BANK vs PAYMENT_GATEWAY; merchant MER006;"))
        self.assertIn("BANK: 1 row, total 4925.00 INR", text)
        self.assertIn("PAYMENT_GATEWAY: 1 row, total 5000.00 INR", text)
        self.assertTrue(text.endswith("amount gap 75.00 INR"))

    def test_split_case_totals_each_source(self) -> None:
        text = describe_case(get_transaction_group("settlement_id", "SET-SPLIT-001"))
        self.assertIn("BANK: 2 rows, total 10000.00 INR", text)
        self.assertTrue(text.endswith("amount gap 0.00 INR"))

    def test_one_sided_group(self) -> None:
        text = describe_case(get_transaction_group("transaction_id", "TXN-SPLIT-001"))
        self.assertTrue(text.startswith("only PAYMENT_GATEWAY rows, no counterpart"))
        self.assertNotIn("amount gap", text)

    def test_empty_group(self) -> None:
        self.assertEqual(describe_case([]), "No canonical rows found for this case.")

    def test_ids_are_left_out_and_order_does_not_matter(self) -> None:
        rows = get_transaction_group("settlement_id", "SET-FEE-001")
        text = describe_case(rows)
        self.assertEqual(describe_case(list(reversed(rows))), text)
        for row in rows:
            self.assertNotIn(row["transaction_id"], text)
            self.assertNotIn(row["event_id"], text)

    def test_mixed_currencies_give_no_gap(self) -> None:
        rows = [
            {"source": "BANK", "amount_value": Decimal("10"), "amount_currency": "INR", "merchant_id": None,
             "status": "SETTLED", "canonical_status": "COMPLETED", "transaction_type": None},
            {"source": "PAYMENT_GATEWAY", "amount_value": Decimal("10"), "amount_currency": "USD", "merchant_id": None,
             "status": "SUCCESS", "canonical_status": "COMPLETED", "transaction_type": None},
        ]
        self.assertTrue(describe_case(rows).endswith("mixed or missing currencies, no amount gap computed"))


@unittest.skipUnless(os.environ.get("RUN_GEMINI_TESTS") == "1", "set RUN_GEMINI_TESTS=1 to call the real Gemini API")
class RealGeminiTest(PastCasesTestBase):
    """Calls the real embedding API (3 calls). Off by default so the normal test run spends no quota."""

    def test_fee_case_is_found_from_a_similar_fee_case(self) -> None:
        from app.tools.similar_cases import GeminiEmbedder

        embedder = GeminiEmbedder()
        fee_case, split_case = self.new_case_id(), self.new_case_id()
        fee_text = describe_case(get_transaction_group("settlement_id", "SET-FEE-001"))
        split_text = describe_case(get_transaction_group("settlement_id", "SET-SPLIT-001"))
        save_resolved_case(fee_case, "RESOLVED", ["TXN-FEE-001", "BANK-FEE-001"], fee_text,
                           "Gateway deducted a 1.5% processing fee before settlement", embedder=embedder)
        save_resolved_case(split_case, "RESOLVED", ["TXN-SPLIT-001"], split_text,
                           "One payment settled by the bank in two parts", embedder=embedder)

        query = fee_text.replace("4925.00", "9850.00").replace("5000.00", "10000.00").replace("75.00", "150.00")
        results = [r for r in find_similar_cases(query, embedder=embedder) if r["case_id"] in self.created]
        self.assertEqual([r["case_id"] for r in results], [fee_case, split_case])
        print("\nreal Gemini distances:", [(r["case_id"][:14], round(r["distance"], 4)) for r in results])


if __name__ == "__main__":
    unittest.main()
