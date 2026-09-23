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
                        "TXN-003",
                        "PAYMENT_GATEWAY",
                        "PAY-2026082277881",
                        "PAY2026082277881",
                        "1000.00",
                        "INR"
                );

        CanonicalTransactionRecord bank =
                transaction(
                        "BANK-3",
                        "TXN-003",
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