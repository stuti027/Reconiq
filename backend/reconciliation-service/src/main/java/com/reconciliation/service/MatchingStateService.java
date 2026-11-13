package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingState;
import com.reconciliation.repository.RedisMatchingStateRepository;
import org.springframework.stereotype.Service;

@Service
public class MatchingStateService {

    private final RedisMatchingStateRepository repository;

    public MatchingStateService(
            RedisMatchingStateRepository repository) {
        this.repository = repository;
    }

    public void addTransaction(
            CanonicalTransactionRecord transaction) {

        if (transaction == null ||
                transaction.getEventId() == null ||
                transaction.getSource() == null) {
            return;
        }

        if ("PAYMENT_GATEWAY".equals(transaction.getSource())) {

            repository.saveGatewayTransaction(transaction);

        } else if ("BANK".equals(transaction.getSource())) {

            repository.saveBankTransaction(transaction);
        }
    }

    public MatchingState getState() {

        MatchingState state = new MatchingState();

        for (CanonicalTransactionRecord transaction :
                repository.getAllGatewayTransactions()) {

            state.addGatewayTransaction(transaction);
        }

        for (CanonicalTransactionRecord transaction :
                repository.getAllBankTransactions()) {

            state.addBankTransaction(transaction);
        }

        for (String eventId :
                repository.getReconciledEventIds()) {

            state.markReconciled(eventId);
        }

        return state;
    }

    public void persistReconciled(
            MatchingState state) {

        if (state == null) {
            return;
        }

        for (String eventId :
                state.getReconciledEventIds()) {

            repository.markReconciled(eventId);
        }
    }
}