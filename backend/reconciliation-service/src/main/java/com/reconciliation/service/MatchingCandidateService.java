package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingState;

import java.util.ArrayList;
import java.util.List;

public class MatchingCandidateService {

    public List<CanonicalTransactionRecord> findCandidates(
            CanonicalTransactionRecord transaction,
            MatchingState state) {

        if (transaction == null ||
                transaction.getSource() == null) {
            return List.of();
        }

        List<CanonicalTransactionRecord> candidates =
                new ArrayList<>();

        List<CanonicalTransactionRecord> oppositeTransactions;

        if ("PAYMENT_GATEWAY".equals(transaction.getSource())) {

            oppositeTransactions =
                    state.getBankTransactions();

        } else if ("BANK".equals(transaction.getSource())) {

            oppositeTransactions =
                    state.getGatewayTransactions();

        } else {

            return List.of();
        }

        for (CanonicalTransactionRecord candidate : oppositeTransactions) {
            if (state.isReconciled(candidate.getEventId())) {
                continue;
            }

            if (isCandidate(transaction, candidate)) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    private boolean isCandidate(
            CanonicalTransactionRecord transaction,
            CanonicalTransactionRecord candidate) {

        if (sameTransactionId(transaction, candidate)) {
            return true;
        }

        if (sameReferenceId(transaction, candidate)) {
            return true;
        }

        if (sameReferenceKey(transaction, candidate)) {
            return true;
        }

        if (sameSettlementId(transaction, candidate)) {
            return true;
        }

        return false;
    }

    private boolean sameTransactionId(
            CanonicalTransactionRecord first,
            CanonicalTransactionRecord second) {

        return first.getTransactionId() != null &&
                first.getTransactionId()
                        .equals(second.getTransactionId());
    }

    private boolean sameReferenceId(
            CanonicalTransactionRecord first,
            CanonicalTransactionRecord second) {

        return first.getReferenceId() != null &&
                first.getReferenceId()
                        .equals(second.getReferenceId());
    }

    private boolean sameReferenceKey(
            CanonicalTransactionRecord first,
            CanonicalTransactionRecord second) {

        return first.getReferenceKey() != null &&
                first.getReferenceKey()
                        .equals(second.getReferenceKey());
    }

    private boolean sameSettlementId(
            CanonicalTransactionRecord first,
            CanonicalTransactionRecord second) {

        return first.getSettlementId() != null &&
                first.getSettlementId()
                        .equals(second.getSettlementId());
    }
}