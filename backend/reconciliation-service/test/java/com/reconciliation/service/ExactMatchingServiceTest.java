package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExactMatchingServiceTest {

    private final ExactMatchingService matchingService =
            new ExactMatchingService();

    @Test
    void shouldMatchWhenAllFieldsAreSame() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "TXN00001",
                "REF00001",
                "REF00001",
                1000.00,
                1000.00,
                "INR",
                "INR",
                "2026-08-25T10:00:00Z",
                "2026-08-25T10:00:00Z"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());
        assertEquals(0.0, result.getAmountDifference());
        assertEquals(null, result.getDiscrepancyDetails());
    }

    @Test
    void shouldNotMatchWhenAmountIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "TXN00001",
                "REF00001",
                "REF00001",
                1000.00,
                950.00,
                "INR",
                "INR",
                "2026-08-25T10:00:00Z",
                "2026-08-25T10:00:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("EXACT", result.getMatchingStrategy());
        assertEquals(50.0, result.getAmountDifference());
        assertEquals("Amount mismatch", result.getDiscrepancyDetails());
    }

    @Test
    void shouldNotMatchWhenReferenceIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "TXN00001",
                "REF00001",
                "REF00099",
                1000.00,
                1000.00,
                "INR",
                "INR",
                "2026-08-25T10:00:00Z",
                "2026-08-25T10:00:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Reference mismatch", result.getDiscrepancyDetails());
    }

    @Test
    void shouldNotMatchWhenCurrencyIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "TXN00001",
                "REF00001",
                "REF00001",
                1000.00,
                1000.00,
                "INR",
                "USD",
                "2026-08-25T10:00:00Z",
                "2026-08-25T10:00:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Currency mismatch", result.getDiscrepancyDetails());
    }
}