package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OneToManyMatchingServiceTest {

    private final OneToManyMatchingService matchingService =
            new OneToManyMatchingService();

    @Test
    void shouldMatchOneGatewayTransactionWithMultipleBankTransactions() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of("BANK-001", "BANK-002"),
                List.of("REF-SPLIT-001", "REF-SPLIT-001"),
                List.of(6000.00, 4000.00),
                "INR"
        );

        assertEquals("MATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(0.0, result.getAmountDifference());
        assertEquals(null, result.getDiscrepancyDetails());
        assertEquals(
                "BANK-001,BANK-002",
                result.getBankTransactionId()
        );
    }

    @Test
    void shouldNotMatchWhenTotalBankAmountIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of("BANK-001", "BANK-002"),
                List.of("REF-SPLIT-001", "REF-SPLIT-001"),
                List.of(6000.00, 3500.00),
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(500.0, result.getAmountDifference());
        assertEquals(
                "Total settlement amount mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenBankReferenceIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of("BANK-001", "BANK-002"),
                List.of("REF-SPLIT-001", "REF-WRONG"),
                List.of(6000.00, 4000.00),
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(
                "Reference mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenCurrencyIsDifferent() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of("BANK-001", "BANK-002"),
                List.of("REF-SPLIT-001", "REF-SPLIT-001"),
                List.of(6000.00, 4000.00),
                "USD"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(
                "Currency mismatch",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenBankTransactionDataIsInconsistent() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of("BANK-001", "BANK-002"),
                List.of("REF-SPLIT-001"),
                List.of(6000.00, 4000.00),
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(
                "Bank transaction data is inconsistent",
                result.getDiscrepancyDetails()
        );
    }

    @Test
    void shouldNotMatchWhenNoBankTransactionsAreProvided() {

        MatchingResult result = matchingService.match(
                "TXN-SPLIT-001",
                "REF-SPLIT-001",
                10000.00,
                "INR",
                List.of(),
                List.of(),
                List.of(),
                "INR"
        );

        assertEquals("UNMATCHED", result.getStatus());
        assertEquals("1_TO_MANY", result.getMatchingStrategy());
        assertEquals(
                "No bank transactions provided",
                result.getDiscrepancyDetails()
        );
    }
}