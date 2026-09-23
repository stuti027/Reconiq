package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingState;

public class MatchingStateService {

    private final MatchingState state;

    public MatchingStateService() {
        this.state = new MatchingState();
    }

    public void addTransaction(
            CanonicalTransactionRecord transaction) {

        if (transaction == null ||
                transaction.getSource() == null) {
            return;
        }

        if ("PAYMENT_GATEWAY".equals(transaction.getSource())) {

            state.addGatewayTransaction(transaction);

        } else if ("BANK".equals(transaction.getSource())) {

            state.addBankTransaction(transaction);
        }
    }

    public MatchingState getState() {
        return state;
    }
}