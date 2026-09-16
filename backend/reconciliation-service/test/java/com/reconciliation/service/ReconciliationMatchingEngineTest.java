package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReconciliationMatchingEngineTest {

    private final ReconciliationMatchingEngine engine =
            new ReconciliationMatchingEngine();

    @Test
    void shouldExecuteExactMatching() {

        MatchingResult result = engine.exactMatch(
                "TXN00001",
                "BANK00001",
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
    }

    @Test
    void shouldExecuteTimeWindowMatching() {

        MatchingResult result = engine.timeWindowMatch(
                "TXN00001",
                "BANK00001",
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
    }

    @Test
    void shouldExecuteOneToOneMatching() {

        MatchingResult result = engine.oneToOneMatch(
                "TXN00001",
                "BANK00001",
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
        assertEquals("1_TO_1", result.getMatchingStrategy());
    }

    @Test
    void shouldExecuteOneToManyMatching() {

        MatchingResult result = engine.oneToManyMatch(
                "TXN00001",
                "REF00001",
                10000.00,
                "INR",
                List.of("BANK00001", "BANK00002"),
                List.of("REF00001", "REF00001"),
                List.of(6000.00, 4000.00),
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
    }

    @Test
    void shouldExecuteManyToOneMatching() {

        MatchingResult result = engine.manyToOneMatch(
                List.of("TXN00001", "TXN00002"),
                List.of("REF00001", "REF00001"),
                List.of(4000.00, 6000.00),
                "INR",
                "BANK00001",
                "REF00001",
                10000.00,
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
    }

    @Test
    void shouldExecutePartialAmountMatching() {

        MatchingResult result = engine.partialAmountMatch(
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
    }

    @Test
    void shouldExecuteDuplicateDetection() {

        MatchingResult result = engine.duplicateCheck(
                "TXN00001",
                "REF00001",
                "TXN00001",
                "REF00099"
        );

        assertEquals("DUPLICATE", result.getStatus());
        assertEquals("DUPLICATE", result.getMatchingStrategy());
    }

    @Test
    void shouldExecuteFuzzyMatching() {

        MatchingResult result = engine.fuzzyMatch(
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
    }
}