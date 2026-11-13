package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import com.reconciliation.model.MatchingState;

import java.util.ArrayList;
import java.util.List;

public class ReconciliationProcessor {

    private final MatchingCandidateService candidateService;

    private final MatchingStrategyDispatcher strategyDispatcher;

    private final ReconciliationMatchingEngine matchingEngine;

    public ReconciliationProcessor() {
        this.candidateService = new MatchingCandidateService();
        this.strategyDispatcher = new MatchingStrategyDispatcher();
        this.matchingEngine = new ReconciliationMatchingEngine();
    }

    public List<MatchingResult> process(
            CanonicalTransactionRecord transaction,
            MatchingState state) {

        List<MatchingResult> results = new ArrayList<>();

        if (transaction == null || state == null) {
            return results;
        }

        /*
         * Step 1:
         * Check for duplicates within the SAME source.
         *
         * A gateway transaction is compared only with previous
         * gateway transactions.
         *
         * A bank transaction is compared only with previous
         * bank transactions.
         *
         * Gateway and bank records are intentionally not checked
         * against each other here because they are expected to
         * represent the same business transaction.
         */
        MatchingResult duplicateResult =
                findDuplicate(transaction, state);

        if (duplicateResult != null) {
            results.add(duplicateResult);

            state.markReconciled(
                    transaction.getEventId()
            );

            return results;
        }

        /*
         * Step 2:
         * Find possible transactions from the opposite source.
         */
        List<CanonicalTransactionRecord> candidates =
                candidateService.findCandidates(
                        transaction,
                        state
                );

        /*
         * Step 3:
         * Try grouped settlement matching first.
         *
         * This handles:
         * - 1:N
         * - N:1
         */
        MatchingResult groupedResult =
                processGroupedMatch(
                        transaction,
                        candidates,
                        state
                );

        if (groupedResult != null) {

            results.add(groupedResult);

            if (isSuccessful(groupedResult)) {

                markReconciled(
                        transaction,
                        candidates,
                        groupedResult,
                        state
                );
            }

            return results;
        }

        /*
         * Step 4:
         * Fall back to pairwise strategies.
         */
        for (CanonicalTransactionRecord candidate : candidates) {

            CanonicalTransactionRecord gateway;

            CanonicalTransactionRecord bank;

            if ("PAYMENT_GATEWAY".equals(
                    transaction.getSource())) {

                gateway = transaction;
                bank = candidate;

            } else {

                gateway = candidate;
                bank = transaction;
            }

            MatchingResult result =
                    strategyDispatcher.match(
                            gateway,
                            bank
                    );

            if (result != null) {

                results.add(result);

                if (isSuccessful(result)) {

                    state.markReconciled(
                            gateway.getEventId()
                    );

                    state.markReconciled(
                            bank.getEventId()
                    );

                    /*
                     * Once a successful pair is found, there is
                     * no reason to attempt additional candidates
                     * for this transaction.
                     */
                    break;
                }
            }
        }

        if (results.isEmpty()) {
                String transactionId = transaction.getTransactionId();

                String gatewayTransactionId =
                        "PAYMENT_GATEWAY".equals(transaction.getSource())
                                ? transactionId
                                : null;

                String bankTransactionId =
                        "BANK".equals(transaction.getSource())
                                ? transactionId
                                : null;

                results.add(new MatchingResult(
                        "UNMATCHED",
                        gatewayTransactionId,
                        bankTransactionId,
                        "NO_MATCH",
                        0.0,
                        0,
                        "No matching transaction found"
                ));
                }

                return results;
    }

    private MatchingResult findDuplicate(
            CanonicalTransactionRecord transaction,
            MatchingState state) {

        List<CanonicalTransactionRecord> existingTransactions;

        if ("PAYMENT_GATEWAY".equals(
                transaction.getSource())) {

            existingTransactions =
                    state.getGatewayTransactions();

        } else if ("BANK".equals(
                transaction.getSource())) {

            existingTransactions =
                    state.getBankTransactions();

        } else {

            return null;
        }

        for (CanonicalTransactionRecord existing :
                existingTransactions) {

            /*
             * Do not compare an event with itself.
             */
            if (existing.getEventId() != null &&
                    existing.getEventId().equals(
                            transaction.getEventId())) {

                continue;
            }

            MatchingResult result =
                    matchingEngine.duplicateCheck(
                            transaction.getTransactionId(),
                            transaction.getReferenceId(),
                            existing.getTransactionId(),
                            existing.getReferenceId()
                    );

            if (result != null &&
                    "DUPLICATE".equals(
                            result.getStatus())) {

                return result;
            }
        }

        return null;
    }

    private MatchingResult processGroupedMatch(
            CanonicalTransactionRecord transaction,
            List<CanonicalTransactionRecord> candidates,
            MatchingState state) {

        if (transaction == null ||
                candidates == null ||
                candidates.size() < 2) {

            return null;
        }

        if (!hasSettlementId(transaction)) {
            return null;
        }

        List<CanonicalTransactionRecord>
                settlementCandidates =
                new ArrayList<>();

        for (CanonicalTransactionRecord candidate :
                candidates) {

            if (candidate == null) {
                continue;
            }

            if (!hasSettlementId(candidate)) {
                continue;
            }

            if (!transaction.getSettlementId().equals(
                    candidate.getSettlementId())) {

                continue;
            }

            if (state.isReconciled(
                    candidate.getEventId())) {

                continue;
            }

            settlementCandidates.add(candidate);
        }

        if (settlementCandidates.size() < 2) {
            return null;
        }

        /*
         * One gateway -> multiple bank transactions.
         */
        if ("PAYMENT_GATEWAY".equals(
                transaction.getSource())) {

            if (transaction.getAmount() == null ||
                    transaction.getCurrency() == null ||
                    transaction.getReferenceId() == null) {

                return null;
            }

            List<String> bankTransactionIds =
                    new ArrayList<>();

            List<String> bankReferenceIds =
                    new ArrayList<>();

            List<Double> bankAmounts =
                    new ArrayList<>();

            for (CanonicalTransactionRecord bank :
                    settlementCandidates) {

                if (bank.getAmount() == null ||
                        bank.getReferenceId() == null ||
                        bank.getCurrency() == null) {

                    return null;
                }

                bankTransactionIds.add(
                        bank.getTransactionId()
                );

                bankReferenceIds.add(
                        bank.getReferenceId()
                );

                bankAmounts.add(
                        bank.getAmount().doubleValue()
                );
            }

            return matchingEngine.oneToManyMatch(
                    transaction.getTransactionId(),
                    transaction.getReferenceId(),
                    transaction.getAmount().doubleValue(),
                    transaction.getCurrency(),
                    bankTransactionIds,
                    bankReferenceIds,
                    bankAmounts,
                    settlementCandidates
                            .get(0)
                            .getCurrency()
            );
        }

        /*
         * Multiple gateway transactions -> one bank transaction.
         */
        if ("BANK".equals(transaction.getSource())) {

            if (transaction.getAmount() == null ||
                    transaction.getCurrency() == null ||
                    transaction.getReferenceId() == null) {

                return null;
            }

            List<String> gatewayTransactionIds =
                    new ArrayList<>();

            List<String> gatewayReferenceIds =
                    new ArrayList<>();

            List<Double> gatewayAmounts =
                    new ArrayList<>();

            for (CanonicalTransactionRecord gateway :
                    settlementCandidates) {

                if (gateway.getAmount() == null ||
                        gateway.getReferenceId() == null ||
                        gateway.getCurrency() == null) {

                    return null;
                }

                gatewayTransactionIds.add(
                        gateway.getTransactionId()
                );

                gatewayReferenceIds.add(
                        gateway.getReferenceId()
                );

                gatewayAmounts.add(
                        gateway.getAmount().doubleValue()
                );
            }

            return matchingEngine.manyToOneMatch(
                    gatewayTransactionIds,
                    gatewayReferenceIds,
                    gatewayAmounts,
                    settlementCandidates
                            .get(0)
                            .getCurrency(),
                    transaction.getTransactionId(),
                    transaction.getReferenceId(),
                    transaction.getAmount().doubleValue(),
                    transaction.getCurrency()
            );
        }

        return null;
    }

    private void markReconciled(
            CanonicalTransactionRecord transaction,
            List<CanonicalTransactionRecord> candidates,
            MatchingResult result,
            MatchingState state) {

        state.markReconciled(
                transaction.getEventId()
        );

        if (hasSettlementId(transaction)) {

            for (CanonicalTransactionRecord candidate :
                    candidates) {

                if (candidate == null) {
                    continue;
                }

                if (transaction.getSettlementId().equals(
                        candidate.getSettlementId())) {

                    state.markReconciled(
                            candidate.getEventId()
                    );
                }
            }
        }
    }

    private boolean isSuccessful(
            MatchingResult result) {

        return result != null &&
                ("MATCHED".equals(
                        result.getStatus()) ||

                 "MATCHED_WITH_DIFFERENCE".equals(
                         result.getStatus()) ||

                 "DUPLICATE".equals(
                         result.getStatus()));
    }

    private boolean hasSettlementId(
            CanonicalTransactionRecord transaction) {

        return transaction != null &&
                transaction.getSettlementId() != null &&
                !transaction.getSettlementId().isBlank();
    }
}