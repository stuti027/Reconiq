package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeWindowMatchingServiceTest {

    private final TimeWindowMatchingService matchingService =
            new TimeWindowMatchingService();

    @Test
    void shouldMatchWhenTimestampsAreWithinFiveMinutes() {

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
                "2026-08-25T10:03:00Z"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("TIME_WINDOW", result.getMatchingStrategy());
        assertEquals(180, result.getTimeDifferenceSeconds());
    }

    @Test
    void shouldMatchWhenTimestampsAreExactlyFiveMinutesApart() {

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
                "2026-08-25T10:05:00Z"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("TIME_WINDOW", result.getMatchingStrategy());
        assertEquals(300, result.getTimeDifferenceSeconds());
    }

    @Test
    void shouldNotMatchWhenTimestampsAreOutsideFiveMinutes() {

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
                "2026-08-25T10:06:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("TIME_WINDOW", result.getMatchingStrategy());
        assertEquals(360, result.getTimeDifferenceSeconds());
        assertEquals(
                "Timestamp outside 5-minute window",
                result.getDiscrepancyDetails()
        );
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
                "2026-08-25T10:02:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Reference mismatch", result.getDiscrepancyDetails());
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
                "2026-08-25T10:02:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Amount mismatch", result.getDiscrepancyDetails());
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
                "2026-08-25T10:02:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Currency mismatch", result.getDiscrepancyDetails());
    }

    @Test
    void shouldNotMatchWhenTimestampIsInvalid() {

        MatchingResult result = matchingService.match(
                "TXN00001",
                "TXN00001",
                "REF00001",
                "REF00001",
                1000.00,
                1000.00,
                "INR",
                "INR",
                "invalid-timestamp",
                "2026-08-25T10:02:00Z"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("Invalid timestamp", result.getDiscrepancyDetails());
    }
}