package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartialAmountMatchingServiceTest {

    private final PartialAmountMatchingService matchingService =
            new PartialAmountMatchingService();

    @Test
    void shouldMatchWhenAmountDifferenceIsWithinAllowedThreshold() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00001",
                1000.00,
                950.00,
                "INR",
                "INR"
        );

        assertEquals("MATCHED_WITH_DIFFERENCE", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals(50.00, result.getAmountDifference());
        assertEquals(
                "Amount difference within allowed threshold",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldMatchWhenAmountDifferenceIsLessThanThreshold() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00001",
                1000.00,
                975.00,
                "INR",
                "INR"
        );

        assertEquals("MATCHED_WITH_DIFFERENCE", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals(25.00, result.getAmountDifference());
    }

    @Test
    void shouldNotMatchWhenAmountDifferenceExceedsThreshold() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00001",
                1000.00,
                940.00,
                "INR",
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals(60.00, result.getAmountDifference());
        assertEquals(
                "Amount difference exceeds allowed threshold",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenReferenceIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00099",
                1000.00,
                950.00,
                "INR",
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals("Reference mismatch", result.getDiscrepancyDetails());
    }

    @Test
    void shouldNotMatchWhenCurrencyIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00001",
                1000.00,
                950.00,
                "INR",
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals("Currency mismatch", result.getDiscrepancyDetails());
    }

    @Test
    void shouldReportMultipleDiscrepancies() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "BANK00001",
                "REF00001",
                "REF00099",
                1000.00,
                900.00,
                "INR",
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("PARTIAL_AMOUNT", result.getMatchingStrategy());
        assertEquals(100.00, result.getAmountDifference());
        assertEquals(
                "Reference mismatch; Amount difference exceeds allowed threshold; Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }
}