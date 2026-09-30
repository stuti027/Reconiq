package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FuzzyMatchingServiceTest {

    private final FuzzyMatchingService matchingService =
            new FuzzyMatchingService();

    @Test
    void shouldMatchWhenReferencesAreHighlySimilar() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "PAY2026082277881",
                "PAY202608227788I",
                1000.00,
                1000.00,
                "INR",
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals(0.0, result.getAmountDifference());
    }

    @Test
    void shouldMatchWhenReferencesAreExactlySame() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00001",
                1000.00,
                1000.00,
                "INR",
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
    }

    @Test
    void shouldNotMatchWhenReferencesAreTooDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "COMPLETELY_DIFFERENT",
                1000.00,
                1000.00,
                "INR",
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals(
                "Reference similarity below threshold",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenAmountIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00002",
                1000.00,
                950.00,
                "INR",
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals(50.00, result.getAmountDifference());
        assertEquals(
                "Amount mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenCurrencyIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00002",
                1000.00,
                1000.00,
                "INR",
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals(
                "Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldReportMultipleDiscrepancies() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "COMPLETELY_DIFFERENT",
                1000.00,
                900.00,
                "INR",
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("FUZZY", result.getMatchingStrategy());
        assertEquals(100.00, result.getAmountDifference());
        assertEquals(
                "Reference similarity below threshold; Amount mismatch; Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenReferenceIsNull() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                null,
                "REF00001",
                1000.00,
                1000.00,
                "INR",
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals(
                "Reference similarity below threshold",
                result.getDiscrepancyDetails()
        );
    }
}