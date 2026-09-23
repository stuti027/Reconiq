package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;

public class MatchingStrategyDispatcher {

    private final ReconciliationMatchingEngine matchingEngine;

    public MatchingStrategyDispatcher() {
        this.matchingEngine = new ReconciliationMatchingEngine();
    }

    public MatchingResult match(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        if (gateway == null || bank == null) {
            return null;
        }

        /*
         * Exact matching is attempted first.
         */
        if (hasAmount(gateway, bank) &&
                sameReferenceId(gateway, bank) &&
                sameAmount(gateway, bank) &&
                sameCurrency(gateway, bank) &&
                hasTimestamps(gateway, bank)) {

            return matchingEngine.exactMatch(
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    gateway.getReferenceId(),
                    bank.getReferenceId(),
                    gateway.getAmount().doubleValue(),
                    bank.getAmount().doubleValue(),
                    gateway.getCurrency(),
                    bank.getCurrency(),
                    gateway.getEventTimestamp().toString(),
                    bank.getEventTimestamp().toString()
            );
        }

        /*
         * 1:1 matching is attempted when the two records
         * represent the same transaction and have matching
         * amount and currency.
         */
        if (hasAmount(gateway, bank) &&
                sameReferenceId(gateway, bank) &&
                sameAmount(gateway, bank) &&
                sameCurrency(gateway, bank)) {

            MatchingResult oneToOneResult =
                    matchingEngine.oneToOneMatch(
                            gateway.getTransactionId(),
                            bank.getTransactionId(),
                            gateway.getReferenceId(),
                            bank.getReferenceId(),
                            gateway.getAmount().doubleValue(),
                            bank.getAmount().doubleValue(),
                            gateway.getCurrency(),
                            bank.getCurrency(),
                            timestamp(gateway),
                            timestamp(bank)
                    );

            if (oneToOneResult != null &&
                    "MATCHED".equals(oneToOneResult.getStatus())) {

                return oneToOneResult;
            }
        }

        /*
         * Time-window matching is attempted whenever the
         * reference, currency and timestamps are available.
         *
         * The result is returned even when it is UNMATCHED.
         * This is important because the result contains the
         * actual reconciliation discrepancy, such as
         * "Amount mismatch" or "Timestamp outside 5-minute window".
         */
        if (hasAmount(gateway, bank) &&
                sameReferenceId(gateway, bank) &&
                sameCurrency(gateway, bank) &&
                hasTimestamps(gateway, bank)) {

            return matchingEngine.timeWindowMatch(
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    gateway.getReferenceId(),
                    bank.getReferenceId(),
                    gateway.getAmount().doubleValue(),
                    bank.getAmount().doubleValue(),
                    gateway.getCurrency(),
                    bank.getCurrency(),
                    gateway.getEventTimestamp().toString(),
                    bank.getEventTimestamp().toString()
            );
        }

        /*
         * Partial amount matching is used when the reference
         * and currency match but timestamp-based matching
         * cannot be performed.
         */
        if (hasAmount(gateway, bank) &&
                sameReferenceId(gateway, bank) &&
                sameCurrency(gateway, bank) &&
                !sameAmount(gateway, bank)) {

            MatchingResult partialResult =
                    matchingEngine.partialAmountMatch(
                            gateway.getTransactionId(),
                            bank.getTransactionId(),
                            gateway.getReferenceId(),
                            bank.getReferenceId(),
                            gateway.getAmount().doubleValue(),
                            bank.getAmount().doubleValue(),
                            gateway.getCurrency(),
                            bank.getCurrency()
                    );

            if (partialResult != null &&
                    "MATCHED_WITH_DIFFERENCE".equals(
                            partialResult.getStatus())) {

                return partialResult;
            }
        }

        /*
         * Fuzzy matching is the final pairwise strategy.
         *
         * reference_key is the normalized reference used
         * for fuzzy comparison.
         */
        if (hasAmount(gateway, bank) &&
                hasReferenceKey(gateway, bank) &&
                sameCurrency(gateway, bank)) {

            return matchingEngine.fuzzyMatch(
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    gateway.getReferenceKey(),
                    bank.getReferenceKey(),
                    gateway.getAmount().doubleValue(),
                    bank.getAmount().doubleValue(),
                    gateway.getCurrency(),
                    bank.getCurrency()
            );
        }

        return null;
    }

    private boolean sameReferenceId(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getReferenceId() != null &&
                gateway.getReferenceId().equals(
                        bank.getReferenceId()
                );
    }

    private boolean sameAmount(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getAmount() != null &&
                bank.getAmount() != null &&
                gateway.getAmount().compareTo(
                        bank.getAmount()
                ) == 0;
    }

    private boolean sameCurrency(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getCurrency() != null &&
                gateway.getCurrency().equals(
                        bank.getCurrency()
                );
    }

    private boolean hasAmount(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getAmount() != null &&
                bank.getAmount() != null;
    }

    private boolean hasTimestamps(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getEventTimestamp() != null &&
                bank.getEventTimestamp() != null;
    }

    private boolean hasReferenceKey(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getReferenceKey() != null &&
                bank.getReferenceKey() != null;
    }

    private String timestamp(
            CanonicalTransactionRecord transaction) {

        if (transaction.getEventTimestamp() == null) {
            return null;
        }

        return transaction.getEventTimestamp().toString();
    }
}