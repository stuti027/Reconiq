-- Phase 3 acceptance queries: one per matching rule, run against the real sample data.
-- Verified against the sandbox stack on 2026-09-22 (integration run, plan 2.5 layer 5);
-- all 11 rules returned the expected result. Included for the Phase 3 owner as a fit check
-- for the canonical contract, not as a replacement for the real matcher.

-- 1. Exact: same reference, same amount and currency on both sides.
SELECT transaction_id, source, reference_id, amount_value, amount_currency
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%EXACT%' ORDER BY source;

-- 2. Partial amount (fee): gateway amount differs from bank amount by a fee; bank row carries the description.
SELECT transaction_id, source, reference_id, description, amount_value
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%FEE%' ORDER BY source;

-- 3. 1:N split: one gateway row's amount equals the sum of two bank rows sharing one settlement_id.
SELECT transaction_id, source, settlement_id, reference_id, amount_value
FROM normalization.canonical_transactions WHERE settlement_id LIKE '%SPLIT%' ORDER BY transaction_id;

-- 4. N:1 combine: several gateway rows sum to one bank row sharing one settlement_id.
SELECT transaction_id, source, settlement_id, amount_value
FROM normalization.canonical_transactions WHERE settlement_id LIKE '%COMB%' ORDER BY transaction_id;

-- 5. Currency mismatch: same reference and amount, different currency.
SELECT transaction_id, source, amount_value, amount_currency
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%CURR%' ORDER BY source;

-- 6. Time window: same reference and amount, timestamps a few hours apart.
SELECT transaction_id, source, reference_id, amount_value, event_timestamp
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%TIME%' ORDER BY source;

-- 7. Duplicate: two gateway rows, same reference and amount, distinct transaction_id and event_id.
SELECT transaction_id, event_id, source, reference_id, amount_value
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%DUP%' ORDER BY event_id;

-- 8. Missing amount: bank side has a NULL amount_value, gateway side has a real one.
SELECT transaction_id, source, amount_value
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%MISS%' ORDER BY source;

-- 9. Status discrepancy: canonical_status differs between the two sides for the same transaction.
SELECT transaction_id, source, canonical_status
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%STATUS%' ORDER BY source;

-- 10. Fuzzy reference: different raw reference_id, equal reference_key (upper-cased, punctuation removed).
SELECT transaction_id, source, reference_id, reference_key
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%FUZZY%' ORDER BY source;

-- 11. Orphan: a gateway row with no bank counterpart at all.
SELECT transaction_id, source
FROM normalization.canonical_transactions WHERE transaction_id LIKE '%ORPHAN%';
