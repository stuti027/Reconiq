package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import com.reconciliation.model.MatchingState;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationProcessorTest {

    private final ReconciliationProcessor processor =
            new ReconciliationProcessor();

    @Test
    void shouldProcessMatchingGatewayAndBankTransactions() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-1",
                        "TXN-001",
                        "PAYMENT_GATEWAY",
                        "REF-001",
                        "REF001",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-1",
                        "TXN-001",
                        "BANK",
                        "REF-001",
                        "REF001",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("MATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());
        assertEquals("TXN-001", result.getGatewayTransactionId());
        assertEquals("TXN-001", result.getBankTransactionId());
    }

    @Test
    void shouldProcessBankTransactionAgainstGatewayState() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-2",
                        "TXN-002",
                        "PAYMENT_GATEWAY",
                        "REF-002",
                        "REF002",
                        "1500.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-2",
                        "TXN-002",
                        "BANK",
                        "REF-002",
                        "REF002",
                        "1500.00",
                        "INR"
                );

        MatchingState state = new MatchingState();
        state.addGatewayTransaction(gateway);

        List<MatchingResult> results =
                processor.process(bank, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("MATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());
        assertEquals("TXN-002", result.getGatewayTransactionId());
        assertEquals("TXN-002", result.getBankTransactionId());
    }

    @Test
    void shouldReturnEmptyWhenNoCandidateExists() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-3",
                        "TXN-003",
                        "PAYMENT_GATEWAY",
                        "REF-003",
                        "REF003",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldReturnEmptyForUnknownSource() {

        CanonicalTransactionRecord transaction =
                transaction(
                        "EVENT-1",
                        "TXN-004",
                        "UNKNOWN",
                        "REF-004",
                        "REF004",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();

        List<MatchingResult> results =
                processor.process(transaction, state);

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldNotReconcileSameTransactionsTwice() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-5",
                        "TXN-005",
                        "PAYMENT_GATEWAY",
                        "REF-005",
                        "REF005",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-5",
                        "TXN-005",
                        "BANK",
                        "REF-005",
                        "REF005",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<MatchingResult> firstResults =
                processor.process(gateway, state);

        assertEquals(1, firstResults.size());
        assertEquals("MATCHED", firstResults.get(0).getStatus());

        state.addGatewayTransaction(gateway);

        List<MatchingResult> secondResults =
                processor.process(gateway, state);

        assertTrue(secondResults.isEmpty());

        assertTrue(state.isReconciled("GATEWAY-5"));
        assertTrue(state.isReconciled("BANK-5"));
    }

    @Test
    void shouldMarkBothEventsAsReconciledAfterSuccessfulMatch() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-6",
                        "TXN-006",
                        "PAYMENT_GATEWAY",
                        "REF-006",
                        "REF006",
                        "2000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-6",
                        "TXN-006",
                        "BANK",
                        "REF-006",
                        "REF006",
                        "2000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();
        state.addBankTransaction(bank);

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertEquals(1, results.size());
        assertEquals("MATCHED", results.get(0).getStatus());

        assertTrue(state.isReconciled("GATEWAY-6"));
        assertTrue(state.isReconciled("BANK-6"));
    }

    @Test
    void shouldProcessOneToManySettlement() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-10",
                        "TXN-010",
                        "PAYMENT_GATEWAY",
                        "REF-010",
                        "REF010",
                        "1000.00",
                        "INR"
                );

        gateway.setSettlementId("SETTLE-010");

        CanonicalTransactionRecord bankOne =
                transaction(
                        "BANK-10",
                        "TXN-010-A",
                        "BANK",
                        "REF-010",
                        "REF010",
                        "400.00",
                        "INR"
                );

        bankOne.setSettlementId("SETTLE-010");

        CanonicalTransactionRecord bankTwo =
                transaction(
                        "BANK-11",
                        "TXN-010-B",
                        "BANK",
                        "REF-010",
                        "REF010",
                        "600.00",
                        "INR"
                );

        bankTwo.setSettlementId("SETTLE-010");

        MatchingState state = new MatchingState();

        state.addBankTransaction(bankOne);
        state.addBankTransaction(bankTwo);

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("MATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());

        assertTrue(state.isReconciled("GATEWAY-10"));
        assertTrue(state.isReconciled("BANK-10"));
        assertTrue(state.isReconciled("BANK-11"));
    }

    @Test
    void shouldProcessManyToOneSettlement() {

        CanonicalTransactionRecord gatewayOne =
                transaction(
                        "GATEWAY-12",
                        "TXN-012-A",
                        "PAYMENT_GATEWAY",
                        "REF-012",
                        "REF012",
                        "400.00",
                        "INR"
                );

        gatewayOne.setSettlementId("SETTLE-012");

        CanonicalTransactionRecord gatewayTwo =
                transaction(
                        "GATEWAY-13",
                        "TXN-012-B",
                        "PAYMENT_GATEWAY",
                        "REF-012",
                        "REF012",
                        "600.00",
                        "INR"
                );

        gatewayTwo.setSettlementId("SETTLE-012");

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-12",
                        "TXN-012",
                        "BANK",
                        "REF-012",
                        "REF012",
                        "1000.00",
                        "INR"
                );

        bank.setSettlementId("SETTLE-012");

        MatchingState state = new MatchingState();

        state.addGatewayTransaction(gatewayOne);
        state.addGatewayTransaction(gatewayTwo);

        List<MatchingResult> results =
                processor.process(bank, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("MATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());

        assertTrue(state.isReconciled("GATEWAY-12"));
        assertTrue(state.isReconciled("GATEWAY-13"));
        assertTrue(state.isReconciled("BANK-12"));
    }

    @Test
    void shouldDetectDuplicateWithinSameGatewaySource() {

        CanonicalTransactionRecord existingGateway =
                transaction(
                        "GATEWAY-20",
                        "TXN-020",
                        "PAYMENT_GATEWAY",
                        "REF-020",
                        "REF020",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord duplicateGateway =
                transaction(
                        "GATEWAY-21",
                        "TXN-020",
                        "PAYMENT_GATEWAY",
                        "REF-021",
                        "REF021",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();

        state.addGatewayTransaction(existingGateway);

        List<MatchingResult> results =
                processor.process(duplicateGateway, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals("DUPLICATE", result.getMatchingStrategy());
        assertEquals(
                "Duplicate transaction ID",
                result.getDiscrepancyDetails()
        );

        assertTrue(
                state.isReconciled("GATEWAY-21")
        );
    }

    @Test
    void shouldNotTreatGatewayAndBankTransactionAsDuplicate() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-22",
                        "TXN-022",
                        "PAYMENT_GATEWAY",
                        "REF-022",
                        "REF022",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-22",
                        "TXN-022",
                        "BANK",
                        "REF-022",
                        "REF022",
                        "1000.00",
                        "INR"
                );

        MatchingState state = new MatchingState();

        state.addBankTransaction(bank);

        List<MatchingResult> results =
                processor.process(gateway, state);

        assertEquals(1, results.size());

        MatchingResult result = results.get(0);

        assertEquals("MATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());

        assertTrue(
                state.isReconciled("GATEWAY-22")
        );

        assertTrue(
                state.isReconciled("BANK-22")
        );
    }

    private CanonicalTransactionRecord transaction(
            String eventId,
            String transactionId,
            String source,
            String referenceId,
            String referenceKey,
            String amount,
            String currency) {

        CanonicalTransactionRecord transaction =
                new CanonicalTransactionRecord();

        transaction.setEventId(eventId);
        transaction.setTransactionId(transactionId);
        transaction.setSource(source);
        transaction.setReferenceId(referenceId);
        transaction.setReferenceKey(referenceKey);
        transaction.setAmount(new BigDecimal(amount));
        transaction.setCurrency(currency);

        transaction.setEventTimestamp(
                Instant.parse("2026-08-25T10:00:00Z")
        );

        return transaction;
    }
}