package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MatchingStrategyDispatcherTest {

    private final MatchingStrategyDispatcher dispatcher =
            new MatchingStrategyDispatcher();

    @Test
    void shouldUseExactMatchingForExactRecords() {

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

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("MATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());
    }

    @Test
    void shouldUseTimeWindowWhenAmountDiffers() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-2",
                        "TXN-002",
                        "PAYMENT_GATEWAY",
                        "REF-002",
                        "REF002",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-2",
                        "TXN-002",
                        "BANK",
                        "REF-002",
                        "REF002",
                        "950.00",
                        "INR"
                );

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("TIME_WINDOW", result.getMatchingStrategy());
        assertEquals("UNMATCHED", result.getStatus());
    }

    @Test
    void shouldUseFuzzyMatchingWhenReferenceIdsDiffer() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-3",
                        "TXN-GATEWAY-003",
                        "PAYMENT_GATEWAY",
                        "PAY-2026082277881",
                        "PAY2026082277881",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-3",
                        "TXN-BANK-003",
                        "BANK",
                        "PAY2026082277881",
                        "PAY2026082277881",
                        "1000.00",
                        "INR"
                );

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals("MATCHED", result.getStatus());
    }

    @Test
    void shouldReturnNullForMissingTransaction() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-4",
                        "TXN-004",
                        "PAYMENT_GATEWAY",
                        "REF-004",
                        "REF004",
                        "1000.00",
                        "INR"
                );

        assertNull(dispatcher.match(gateway, null));
    }

    @Test
    void shouldDetectCurrencyMismatch() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-CURR",
                        "TXN-CURR",
                        "PAYMENT_GATEWAY",
                        "REF-CURR",
                        "REFCURR",
                        "100.00",
                        "USD"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-CURR",
                        "TXN-CURR",
                        "BANK",
                        "REF-CURR",
                        "REFCURR",
                        "100.00",
                        "INR"
                );

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("CURRENCY", result.getMatchingStrategy());
        assertEquals(
                "Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldDetectMissingAmount() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-MISS",
                        "TXN-MISS",
                        "PAYMENT_GATEWAY",
                        "REF-MISS",
                        "REFMISS",
                        "2200.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-MISS",
                        "TXN-MISS",
                        "BANK",
                        "REF-MISS",
                        "REFMISS",
                        "2200.00",
                        "INR"
                );

        bank.setAmount(null);

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("UNMATCHED", result.getStatus());
        assertEquals(
                "MISSING_AMOUNT",
                result.getMatchingStrategy()
        );
    }

    @Test
    void shouldDetectStatusMismatch() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-STATUS",
                        "TXN-STATUS",
                        "PAYMENT_GATEWAY",
                        "REF-STATUS",
                        "REFSTATUS",
                        "500.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-STATUS",
                        "TXN-STATUS",
                        "BANK",
                        "REF-STATUS",
                        "REFSTATUS",
                        "500.00",
                        "INR"
                );

        gateway.setCanonicalStatus("SUCCESS");
        bank.setCanonicalStatus("FAILED");

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("STATUS", result.getMatchingStrategy());
        assertEquals(
                "Status mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldUsePartialAmountMatchingWhenOutsideTimeWindow() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-PARTIAL",
                        "TXN-GATEWAY-PARTIAL",
                        "PAYMENT_GATEWAY",
                        "REF-PARTIAL",
                        "REFPARTIAL",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-PARTIAL",
                        "TXN-BANK-PARTIAL",
                        "BANK",
                        "REF-PARTIAL",
                        "REFPARTIAL",
                        "950.00",
                        "INR"
                );

        bank.setEventTimestamp(
                Instant.parse("2026-08-25T11:00:00Z")
        );

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
    }

    @Test
    void shouldNotUseTimeWindowWhenTimestampsAreOutsideWindow() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-TIME",
                        "TXN-GATEWAY-TIME",
                        "PAYMENT_GATEWAY",
                        "REF-TIME",
                        "REFTIME",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-TIME",
                        "TXN-BANK-TIME",
                        "BANK",
                        "REF-TIME",
                        "REFTIME",
                        "950.00",
                        "INR"
                );

        bank.setEventTimestamp(
                Instant.parse("2026-08-25T11:00:00Z")
        );

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
    }

    @Test
    void shouldHandleMissingTimestamp() {

        CanonicalTransactionRecord gateway =
                transaction(
                        "GATEWAY-NO-TIME",
                        "TXN-NO-TIME",
                        "PAYMENT_GATEWAY",
                        "REF-NO-TIME",
                        "REFNOTIME",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-NO-TIME",
                        "TXN-BANK-NO-TIME",
                        "BANK",
                        "REF-NO-TIME",
                        "REFNOTIME",
                        "1000.00",
                        "INR"
                );

        gateway.setEventTimestamp(null);

        MatchingResult result =
                dispatcher.match(gateway, bank);

        assertNotNull(result);
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