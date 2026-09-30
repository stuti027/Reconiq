package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingState;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchingCandidateServiceTest {

    private final MatchingCandidateService service =
            new MatchingCandidateService();

    @Test
    void shouldFindCandidateByTransactionId() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-1",
                        "TXN-001",
                        "PAYMENT_GATEWAY",
                        null,
                        null,
                        null
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-1",
                        "TXN-001",
                        "BANK",
                        null,
                        null,
                        null
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertEquals(1, candidates.size());
        assertEquals("BANK-1", candidates.get(0).getEventId());
    }

    @Test
    void shouldFindCandidateByReferenceId() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-2",
                        "TXN-002",
                        "PAYMENT_GATEWAY",
                        "REF-002",
                        null,
                        null
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-2",
                        "TXN-999",
                        "BANK",
                        "REF-002",
                        null,
                        null
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertEquals(1, candidates.size());
        assertEquals("BANK-2", candidates.get(0).getEventId());
    }

    @Test
    void shouldFindCandidateByReferenceKey() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-3",
                        "TXN-003",
                        "PAYMENT_GATEWAY",
                        null,
                        "PAY2026082277881",
                        null
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-3",
                        "TXN-888",
                        "BANK",
                        null,
                        "PAY2026082277881",
                        null
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertEquals(1, candidates.size());
    }

    @Test
    void shouldFindCandidateBySettlementId() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-4",
                        "TXN-004",
                        "PAYMENT_GATEWAY",
                        null,
                        null,
                        "SET-SPLIT-001"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-4",
                        "BANK-TXN-001",
                        "BANK",
                        null,
                        null,
                        "SET-SPLIT-001"
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertEquals(1, candidates.size());
    }

    @Test
    void shouldNotReturnSameSourceTransactions() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-5",
                        "TXN-005",
                        "PAYMENT_GATEWAY",
                        "REF-005",
                        null,
                        null
                );

        CanonicalTransactionRecord anotherGateway =
                transaction(
                        "GATEWAY-6",
                        "TXN-005",
                        "PAYMENT_GATEWAY",
                        "REF-005",
                        null,
                        null
                );

        MatchingState state = new MatchingState();
        state.addGatewayTransaction(anotherGateway);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenNoCandidateExists() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-7",
                        "TXN-007",
                        "PAYMENT_GATEWAY",
                        "REF-007",
                        null,
                        null
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-7",
                        "TXN-999",
                        "BANK",
                        "REF-999",
                        null,
                        "SET-999"
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<CanonicalTransactionRecord> candidates =
                service.findCandidates(gateway, state);

        assertTrue(candidates.isEmpty());
    }

    private CanonicalTransactionRecord transaction(
            String eventId,
            String transactionId,
            String source,
            String referenceId,
            String referenceKey,
            String settlementId) {

        CanonicalTransactionRecord transaction =
                new CanonicalTransactionRecord();

        transaction.setEventId(eventId);
        transaction.setTransactionId(transactionId);
        transaction.setSource(source);
        transaction.setReferenceId(referenceId);
        transaction.setReferenceKey(referenceKey);
        transaction.setSettlementId(settlementId);

        return transaction;
    }
}