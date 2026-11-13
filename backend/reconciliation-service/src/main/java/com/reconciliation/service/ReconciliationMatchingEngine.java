package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

import java.util.List;

public class ReconciliationMatchingEngine {

    private final ExactMatchingService exactMatchingService;
    private final TimeWindowMatchingService timeWindowMatchingService;
    private final OneToOneMatchingService oneToOneMatchingService;
    private final OneToManyMatchingService oneToManyMatchingService;
    private final ManyToOneMatchingService manyToOneMatchingService;
    private final PartialAmountMatchingService partialAmountMatchingService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final FuzzyMatchingService fuzzyMatchingService;

    public ReconciliationMatchingEngine() {
        this.exactMatchingService = new ExactMatchingService();
        this.timeWindowMatchingService = new TimeWindowMatchingService();
        this.oneToOneMatchingService = new OneToOneMatchingService();
        this.oneToManyMatchingService = new OneToManyMatchingService();
        this.manyToOneMatchingService = new ManyToOneMatchingService();
        this.partialAmountMatchingService = new PartialAmountMatchingService();
        this.duplicateDetectionService = new DuplicateDetectionService();
        this.fuzzyMatchingService = new FuzzyMatchingService();
    }

    public MatchingResult exactMatch(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency,
            String gatewayTimestamp,
            String bankTimestamp) {

        return exactMatchingService.match(
                gatewayTransactionId,
                bankTransactionId,
                gatewayReferenceId,
                bankReferenceId,
                gatewayAmount,
                bankAmount,
                gatewayCurrency,
                bankCurrency,
                gatewayTimestamp,
                bankTimestamp
        );
    }

    public MatchingResult timeWindowMatch(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency,
            String gatewayTimestamp,
            String bankTimestamp) {

        return timeWindowMatchingService.match(
                gatewayTransactionId,
                bankTransactionId,
                gatewayReferenceId,
                bankReferenceId,
                gatewayAmount,
                bankAmount,
                gatewayCurrency,
                bankCurrency,
                gatewayTimestamp,
                bankTimestamp
        );
    }

    public MatchingResult oneToOneMatch(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency,
            String gatewayTimestamp,
            String bankTimestamp) {

        return oneToOneMatchingService.match(
                gatewayTransactionId,
                bankTransactionId,
                gatewayReferenceId,
                bankReferenceId,
                gatewayAmount,
                bankAmount,
                gatewayCurrency,
                bankCurrency,
                gatewayTimestamp,
                bankTimestamp
        );
    }

    public MatchingResult oneToManyMatch(
            String gatewayTransactionId,
            String gatewayReferenceId,
            double gatewayAmount,
            String gatewayCurrency,
            List<String> bankTransactionIds,
            List<String> bankReferenceIds,
            List<Double> bankAmounts,
            String bankCurrency) {

        return oneToManyMatchingService.match(
                gatewayTransactionId,
                gatewayReferenceId,
                gatewayAmount,
                gatewayCurrency,
                bankTransactionIds,
                bankReferenceIds,
                bankAmounts,
                bankCurrency
        );
    }

    public MatchingResult manyToOneMatch(
            List<String> gatewayTransactionIds,
            List<String> gatewayReferenceIds,
            List<Double> gatewayAmounts,
            String gatewayCurrency,
            String bankTransactionId,
            String bankReferenceId,
            double bankAmount,
            String bankCurrency) {

        return manyToOneMatchingService.match(
                gatewayTransactionIds,
                gatewayReferenceIds,
                gatewayAmounts,
                gatewayCurrency,
                bankTransactionId,
                bankReferenceId,
                bankAmount,
                bankCurrency
        );
    }

    public MatchingResult partialAmountMatch(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency) {

        return partialAmountMatchingService.match(
                gatewayTransactionId,
                bankTransactionId,
                gatewayReferenceId,
                bankReferenceId,
                gatewayAmount,
                bankAmount,
                gatewayCurrency,
                bankCurrency
        );
    }

    public MatchingResult duplicateCheck(
            String transactionId,
            String referenceId,
            String existingTransactionId,
            String existingReferenceId) {

        return duplicateDetectionService.checkDuplicate(
                transactionId,
                referenceId,
                existingTransactionId,
                existingReferenceId
        );
    }

    public MatchingResult fuzzyMatch(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency) {

        return fuzzyMatchingService.match(
                gatewayTransactionId,
                bankTransactionId,
                gatewayReferenceId,
                bankReferenceId,
                gatewayAmount,
                bankAmount,
                gatewayCurrency,
                bankCurrency
        );
    }
}