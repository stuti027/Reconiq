package com.reconciliation.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MatchingState {

    private final List<CanonicalTransactionRecord> gatewayTransactions =
            new ArrayList<>();

    private final List<CanonicalTransactionRecord> bankTransactions =
            new ArrayList<>();

    private final Set<String> reconciledEventIds =
            new HashSet<>();

    public List<CanonicalTransactionRecord> getGatewayTransactions() {
        return gatewayTransactions;
    }

    public List<CanonicalTransactionRecord> getBankTransactions() {
        return bankTransactions;
    }

    public void addGatewayTransaction(
            CanonicalTransactionRecord transaction) {

        gatewayTransactions.add(transaction);
    }

    public void addBankTransaction(
            CanonicalTransactionRecord transaction) {

        bankTransactions.add(transaction);
    }

    public void markReconciled(String eventId) {

        if (eventId != null) {
            reconciledEventIds.add(eventId);
        }
    }

    public boolean isReconciled(String eventId) {

        return eventId != null &&
                reconciledEventIds.contains(eventId);
    }

    public Set<String> getReconciledEventIds() {
        return reconciledEventIds;
    }
}