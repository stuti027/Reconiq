package com.reconciliation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import com.reconciliation.model.MatchingState;

class ReconciliationProcessorTest {

    private ReconciliationProcessor processor;
    private MatchingState state;

    @BeforeEach
    void setUp() {
        processor = new ReconciliationProcessor();
        state = new MatchingState();
    }

    @Test
    void shouldMatchGatewayAndBankTransaction() {

        CanonicalTransactionRecord gateway = transaction(
                "event-gateway-001",
                "TXN-001",
                "PAYMENT_GATEWAY",
                "REF-001",
                "1000.00"
        );

        CanonicalTransactionRecord bank = transaction(
                "event-bank-001",
                "TXN-001",
                "BANK",
                "REF-001",
                "1000.00"
        );

        state.addGatewayTransaction(gateway);

        List<MatchingResult> results =
                processor.process(bank, state);

        assertFalse(results.isEmpty());
        assertEquals("MATCHED", results.get(0).getStatus());
    }

    @Test
    void shouldReturnUnmatchedWhenNoCounterpartyExists() {

        CanonicalTransactionRecord gateway = transaction(
                "event-unmatched-001",
                "TXN-UNMATCHED",
                "PAYMENT_GATEWAY",
                "REF-NOMATCH",
                "777.00"
        );

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertFalse(results.isEmpty());
        assertEquals("UNMATCHED", results.get(0).getStatus());
    }

    @Test
    void shouldDetectDuplicateFromSameSource() {

        CanonicalTransactionRecord first = transaction(
                "event-duplicate-001",
                "TXN-DUP-001",
                "PAYMENT_GATEWAY",
                "REF-DUP-001",
                "500.00"
        );

        CanonicalTransactionRecord duplicate = transaction(
                "event-duplicate-002",
                "TXN-DUP-001",
                "PAYMENT_GATEWAY",
                "REF-DUP-001",
                "500.00"
        );

        state.addGatewayTransaction(first);

        List<MatchingResult> results =
                processor.process(duplicate, state);

        assertFalse(results.isEmpty());
        assertEquals("DUPLICATE", results.get(0).getStatus());
    }

    private CanonicalTransactionRecord transaction(
            String eventId,
            String transactionId,
            String source,
            String referenceId,
            String amount) {

        CanonicalTransactionRecord tx =
                new CanonicalTransactionRecord();

        tx.setEventId(eventId);
        tx.setTransactionId(transactionId);
        tx.setSource(source);
        tx.setEventTimestamp(
                Instant.parse("2026-09-30T10:00:00Z")
        );
        tx.setReferenceId(referenceId);
        tx.setReferenceKey(referenceId);
        tx.setTransactionType("PAYMENT");
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrency("INR");
        tx.setStatus("SUCCESS");
        tx.setCanonicalStatus("COMPLETED");
        tx.setMerchantId("MERCHANT-001");
        tx.setCustomerId("CUSTOMER-001");
        tx.setSettlementId("SETTLE-001");
        tx.setDescription("Test transaction");

        return tx;
    }
}