package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import java.time.Duration;

public class MatchingStrategyDispatcher {

    private final ReconciliationMatchingEngine matchingEngine;
    private static final long TIME_WINDOW_SECONDS = 300;

    public MatchingStrategyDispatcher() {
        this.matchingEngine = new ReconciliationMatchingEngine();
    }

    public MatchingResult match(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        if (gateway == null || bank == null) {
            return null;
        }

        // 1. Business identity exists, but statuses disagree
        if (sameBusinessIdentity(gateway, bank)
                && gateway.getCanonicalStatus() != null
                && bank.getCanonicalStatus() != null
                && !gateway.getCanonicalStatus()
                        .equals(bank.getCanonicalStatus())) {

            return new MatchingResult(
                    "UNMATCHED",
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    "STATUS",
                    amountDifference(gateway, bank),
                    0,
                    "Status mismatch"
            );
        }

        // 2. Business identity exists, but amount is missing
        if (sameBusinessIdentity(gateway, bank)
                && (gateway.getAmount() == null
                || bank.getAmount() == null)) {

            return new MatchingResult(
                    "UNMATCHED",
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    "MISSING_AMOUNT",
                    0.0,
                    0,
                    "Missing amount"
            );
        }

        // 3. Business identity exists, but currency differs
        if (sameBusinessIdentity(gateway, bank)
                && gateway.getAmount() != null
                && bank.getAmount() != null
                && gateway.getCurrency() != null
                && bank.getCurrency() != null
                && !gateway.getCurrency().equals(bank.getCurrency())) {

            return new MatchingResult(
                    "UNMATCHED",
                    gateway.getTransactionId(),
                    bank.getTransactionId(),
                    "CURRENCY",
                    gateway.getAmount()
                            .subtract(bank.getAmount())
                            .doubleValue(),
                    0,
                    "Currency mismatch"
            );
        }

        // 4. Exact match
        if (hasAmount(gateway, bank)
                && sameReferenceId(gateway, bank)
                && sameAmount(gateway, bank)
                && sameCurrency(gateway, bank)
                && timestampsWithinWindow(gateway, bank)) {

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

        // 5. One-to-one match
        if (hasAmount(gateway, bank)
                && sameReferenceId(gateway, bank)
                && sameAmount(gateway, bank)
                && sameCurrency(gateway, bank)
                && timestampsWithinWindow(gateway, bank)) {

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

            if (oneToOneResult != null
                    && "MATCHED".equals(oneToOneResult.getStatus())) {

                return oneToOneResult;
            }
        }

        // 6. Time-window match
        if (hasAmount(gateway, bank)
                && sameReferenceId(gateway, bank)
                && sameCurrency(gateway, bank)
                && hasTimestamps(gateway, bank)) {

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

        // 7. Partial amount match
        if (hasAmount(gateway, bank)
                && sameReferenceId(gateway, bank)
                && sameCurrency(gateway, bank)
                && !sameAmount(gateway, bank)) {

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

            if (partialResult != null
                    && "MATCHED_WITH_DIFFERENCE".equals(
                            partialResult.getStatus())) {

                return partialResult;
            }
        }

        // 8. Fuzzy/reference-key match
        if (hasAmount(gateway, bank)
                && hasReferenceKey(gateway, bank)
                && sameCurrency(gateway, bank)) {

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

        return gateway.getReferenceId() != null
                && gateway.getReferenceId().equals(
                        bank.getReferenceId());
    }

    private boolean sameAmount(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getAmount() != null
                && bank.getAmount() != null
                && gateway.getAmount().compareTo(
                        bank.getAmount()) == 0;
    }

    private boolean sameCurrency(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getCurrency() != null
                && gateway.getCurrency().equals(
                        bank.getCurrency());
    }

    private boolean hasAmount(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getAmount() != null
                && bank.getAmount() != null;
    }

    private boolean hasTimestamps(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getEventTimestamp() != null
                && bank.getEventTimestamp() != null;
    }

    private boolean hasReferenceKey(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getReferenceKey() != null
                && bank.getReferenceKey() != null;
    }

    private boolean sameReferenceKey(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getReferenceKey() != null
                && bank.getReferenceKey() != null
                && gateway.getReferenceKey()
                        .equals(bank.getReferenceKey());
    }

    private String timestamp(
            CanonicalTransactionRecord transaction) {

        if (transaction.getEventTimestamp() == null) {
            return null;
        }

        return transaction.getEventTimestamp().toString();
    }

    private boolean timestampsWithinWindow(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        if (!hasTimestamps(gateway, bank)) {
            return false;
        }

        try {
            long difference = Math.abs(
                    Duration.between(
                            gateway.getEventTimestamp(),
                            bank.getEventTimestamp()
                    ).getSeconds()
            );

            return difference <= TIME_WINDOW_SECONDS;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameBusinessIdentity(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return sameTransactionId(gateway, bank)
                || sameReferenceId(gateway, bank)
                || sameReferenceKey(gateway, bank);
    }

    private boolean sameTransactionId(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        return gateway.getTransactionId() != null
                && gateway.getTransactionId()
                        .equals(bank.getTransactionId());
    }

    private double amountDifference(
            CanonicalTransactionRecord gateway,
            CanonicalTransactionRecord bank) {

        if (gateway.getAmount() == null
                || bank.getAmount() == null) {

            return 0.0;
        }

        return gateway.getAmount()
                .subtract(bank.getAmount())
                .doubleValue();
    }
}