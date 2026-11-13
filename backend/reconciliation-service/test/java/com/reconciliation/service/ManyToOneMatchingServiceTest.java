package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManyToOneMatchingServiceTest {

    private final ManyToOneMatchingService matchingService =
            new ManyToOneMatchingService();

    @Test
    void shouldMatchMultipleGatewayTransactionsWithOneBankTransaction() {

        MatchingResult result = matchingService.match(
                List.of("TXN001", "TXN002"),
                List.of("REF-COMBINED-001", "REF-COMBINED-001"),
                List.of(4000.00, 6000.00),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(0.0, result.getAmountDifference());
        assertEquals(null, result.getDiscrepancyDetails());
        assertEquals("TXN001,TXN002", result.getGatewayTransactionId());
    }

    @Test
    void shouldNotMatchWhenTotalGatewayAmountIsDifferent() {

        MatchingResult result = matchingService.match(
                List.of("TXN001", "TXN002"),
                List.of("REF-COMBINED-001", "REF-COMBINED-001"),
                List.of(4000.00, 5500.00),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(-500.0, result.getAmountDifference());
        assertEquals(
                "Total transaction amount mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenGatewayReferenceIsDifferent() {

        MatchingResult result = matchingService.match(
                List.of("TXN001", "TXN002"),
                List.of("REF-COMBINED-001", "REF-WRONG"),
                List.of(4000.00, 6000.00),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(
                "Reference mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenCurrencyIsDifferent() {

        MatchingResult result = matchingService.match(
                List.of("TXN001", "TXN002"),
                List.of("REF-COMBINED-001", "REF-COMBINED-001"),
                List.of(4000.00, 6000.00),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(
                "Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenGatewayTransactionDataIsInconsistent() {

        MatchingResult result = matchingService.match(
                List.of("TXN001", "TXN002"),
                List.of("REF-COMBINED-001"),
                List.of(4000.00, 6000.00),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(
                "Gateway transaction data is inconsistent",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenNoGatewayTransactionsAreProvided() {

        MatchingResult result = matchingService.match(
                List.of(),
                List.of(),
                List.of(),
                "INR",
                "BANK-001",
                "REF-COMBINED-001",
                10000.00,
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("MANY_TO_1", result.getMatchingStrategy());
        assertEquals(
                "No gateway transactions provided",
                result.getDiscrepancyDetails()
        );
    }
}