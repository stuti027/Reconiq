"""Tests for the Postgres evidence tool, run against the sandbox canonical table (Phase 2 sample data)."""

import os
import unittest
from datetime import datetime
from decimal import Decimal
from unittest import mock

import psycopg

from app.main import connect
from app.tools.transactions import COLUMNS, get_transaction_group


def canonical_row_count() -> int:
    with connect() as conn:
        return conn.execute("SELECT count(*) FROM normalization.canonical_transactions").fetchone()[0]


class TransactionGroupTest(unittest.TestCase):
    def test_shared_transaction_id_returns_both_sources(self) -> None:
        rows = get_transaction_group("transaction_id", "TXN00011")
        self.assertEqual(len(rows), 2)
        self.assertEqual({r["source"] for r in rows}, {"BANK", "PAYMENT_GATEWAY"})
        self.assertTrue(all(r["transaction_id"] == "TXN00011" for r in rows))

    def test_fee_case_by_transaction_id_returns_one_side_each(self) -> None:
        gateway = get_transaction_group("transaction_id", "TXN-FEE-001")
        bank = get_transaction_group("transaction_id", "BANK-FEE-001")
        self.assertEqual([r["amount_value"] for r in gateway], [Decimal("5000.00")])
        self.assertEqual([r["amount_value"] for r in bank], [Decimal("4925.00")])

    def test_fee_case_by_settlement_id_returns_the_pair(self) -> None:
        rows = get_transaction_group("settlement_id", "SET-FEE-001")
        amounts = {r["source"]: r["amount_value"] for r in rows}
        self.assertEqual(amounts, {"PAYMENT_GATEWAY": Decimal("5000.00"), "BANK": Decimal("4925.00")})

    def test_fee_case_by_reference_key_returns_the_pair(self) -> None:
        rows = get_transaction_group("reference_key", "REFFEE001")
        self.assertEqual({r["event_id"] for r in rows}, {"evt_gw_fee_001", "evt_bank_fee_001"})

    def test_split_case_by_settlement_id_returns_all_three_rows(self) -> None:
        rows = get_transaction_group("settlement_id", "SET-SPLIT-001")
        self.assertEqual(len(rows), 3)
        bank_total = sum(r["amount_value"] for r in rows if r["source"] == "BANK")
        gateway_total = sum(r["amount_value"] for r in rows if r["source"] == "PAYMENT_GATEWAY")
        self.assertEqual(bank_total, Decimal("10000.00"))
        self.assertEqual(gateway_total, Decimal("10000.00"))

    def test_split_case_by_gateway_transaction_id_finds_only_the_gateway_row(self) -> None:
        rows = get_transaction_group("transaction_id", "TXN-SPLIT-001")
        self.assertEqual([r["event_id"] for r in rows], ["evt_gw_split_001"])

    def test_row_shape_and_types(self) -> None:
        row = get_transaction_group("transaction_id", "TXN-FEE-001")[0]
        self.assertEqual(tuple(row.keys()), COLUMNS)
        self.assertIsInstance(row["amount_value"], Decimal)
        self.assertIsInstance(row["event_timestamp"], datetime)
        self.assertIsNotNone(row["event_timestamp"].tzinfo)

    def test_rows_come_back_oldest_first(self) -> None:
        rows = get_transaction_group("settlement_id", "SET-SPLIT-001")
        order = [(r["event_timestamp"], r["event_id"]) for r in rows]
        self.assertEqual(order, sorted(order))

    def test_same_call_twice_gives_the_same_list(self) -> None:
        first = get_transaction_group("settlement_id", "SET-SPLIT-001")
        second = get_transaction_group("settlement_id", "SET-SPLIT-001")
        self.assertEqual(first, second)

    def test_unknown_value_returns_empty_list(self) -> None:
        self.assertEqual(get_transaction_group("transaction_id", "TXN-DOES-NOT-EXIST"), [])

    def test_match_is_exact(self) -> None:
        self.assertEqual(get_transaction_group("transaction_id", "txn-fee-001"), [])
        self.assertEqual(get_transaction_group("transaction_id", "TXN-FEE"), [])
        self.assertEqual(get_transaction_group("transaction_id", "TXN-FEE-%"), [])

    def test_reuses_a_given_connection(self) -> None:
        with connect() as conn:
            first = get_transaction_group("transaction_id", "TXN00011", conn)
            second = get_transaction_group("reference_key", "REFFEE001", conn)
            self.assertFalse(conn.closed)
        self.assertEqual(len(first), 2)
        self.assertEqual(len(second), 2)


class LookupSafetyTest(unittest.TestCase):
    def test_key_outside_allowlist_is_refused(self) -> None:
        for key in [
            "merchant_id",
            "event_id",
            "",
            "TRANSACTION_ID",
            "transaction_id = transaction_id --",
            "transaction_id; DROP TABLE normalization.canonical_transactions",
        ]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                get_transaction_group(key, "TXN00011")  # type: ignore[arg-type]

    def test_non_string_value_is_refused(self) -> None:
        for value in [11, None, ["TXN00011"]]:
            with self.subTest(value=value), self.assertRaises(TypeError):
                get_transaction_group("transaction_id", value)  # type: ignore[arg-type]

    def test_injection_attempts_in_value_match_nothing_and_change_nothing(self) -> None:
        before = canonical_row_count()
        for value in [
            "' OR '1'='1",
            "TXN00011' --",
            "TXN00011' OR transaction_id LIKE '%",
            "x'; DROP TABLE normalization.canonical_transactions; --",
            "x'; DELETE FROM normalization.canonical_transactions; --",
            "TXN00011\x00",
        ]:
            with self.subTest(value=value):
                self.assertEqual(get_transaction_group("transaction_id", value), [])
        self.assertEqual(canonical_row_count(), before)

    def test_tool_connection_is_read_only(self) -> None:
        captured = {}

        def spy_connect():
            conn = connect()
            captured["conn"] = conn
            return conn

        with mock.patch("app.tools.transactions.connect", spy_connect):
            get_transaction_group("transaction_id", "TXN00011")
        self.assertTrue(captured["conn"].read_only)

    def test_database_down_raises_instead_of_returning_empty(self) -> None:
        with mock.patch.dict(os.environ, {"POSTGRES_PORT": "1"}):
            with self.assertRaises(psycopg.OperationalError):
                get_transaction_group("transaction_id", "TXN00011")


if __name__ == "__main__":
    unittest.main()
