package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateDetectionServiceTest {

    private final DuplicateDetectionService matchingService =
            new DuplicateDetectionService();

    @Test
    void shouldDetectDuplicateWhenTransactionIdMatches() {

        MatchingResult result = matchingService.checkDuplicate(
                "TXN00001",
                "REF00001",
                "TXN00001",
                "REF00099"
        );

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals("DUPLICATE", result.getMatchingStrategy());
        assertEquals(
                "Duplicate transaction ID",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldDetectDuplicateWhenReferenceIdMatches() {

        MatchingResult result = matchingService.checkDuplicate(
                "TXN00001",
                "REF00001",
                "TXN00099",
                "REF00001"
        );

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals("DUPLICATE", result.getMatchingStrategy());
        assertEquals(
                "Duplicate reference ID",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldDetectDuplicateWhenBothIdsMatch() {

        MatchingResult result = matchingService.checkDuplicate(
                "TXN00001",
                "REF00001",
                "TXN00001",
                "REF00001"
        );

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals(
                "Duplicate transaction ID; Duplicate reference ID",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotDetectDuplicateWhenIdsAreDifferent() {

        MatchingResult result = matchingService.checkDuplicate(
                "TXN00001",
                "REF00001",
                "TXN00099",
                "REF00099"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("DUPLICATE", result.getMatchingStrategy());
        assertEquals(
                "No duplicate detected",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldHandleNullTransactionId() {

        MatchingResult result = matchingService.checkDuplicate(
                null,
                "REF00001",
                "TXN00099",
                "REF00099"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("No duplicate detected", result.getDiscrepancyDetails());
    }

    @Test
    void shouldHandleNullReferenceId() {

        MatchingResult result = matchingService.checkDuplicate(
                "TXN00001",
                null,
                "TXN00099",
                "REF00099"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("No duplicate detected", result.getDiscrepancyDetails());
    }
}