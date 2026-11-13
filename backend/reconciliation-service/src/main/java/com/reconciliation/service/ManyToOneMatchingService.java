package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

import java.util.List;

public class ManyToOneMatchingService {

    public MatchingResult match(
            List<String> gatewayTransactionIds,
            List<String> gatewayReferenceIds,
            List<Double> gatewayAmounts,
            String gatewayCurrency,
            String bankTransactionId,
            String bankReferenceId,
            double bankAmount,
            String bankCurrency) {

        if (gatewayTransactionIds == null ||
                gatewayReferenceIds == null ||
                gatewayAmounts == null ||
                gatewayTransactionIds.isEmpty()) {

            return new MatchingResult(
                    "UNMATCHED",
                    null,
                    bankTransactionId,
                    "MANY_TO_1",
                    bankAmount,
                    0,
                    "No gateway transactions provided"
            );
        }

        if (gatewayTransactionIds.size() != gatewayAmounts.size() ||
                gatewayTransactionIds.size() != gatewayReferenceIds.size()) {

            return new MatchingResult(
                    "UNMATCHED",
                    String.join(",", gatewayTransactionIds),
                    bankTransactionId,
                    "MANY_TO_1",
                    bankAmount,
                    0,
                    "Gateway transaction data is inconsistent"
            );
        }

        boolean currencyMatches =
                gatewayCurrency != null &&
                gatewayCurrency.equals(bankCurrency);

        double totalGatewayAmount = 0.0;

        for (double amount : gatewayAmounts) {
            totalGatewayAmount += amount;
        }

        double amountDifference =
                totalGatewayAmount - bankAmount;

        boolean amountMatches =
                totalGatewayAmount == bankAmount;

        boolean referenceMatches = true;

        for (String referenceId : gatewayReferenceIds) {
            if (bankReferenceId == null ||
                    !bankReferenceId.equals(referenceId)) {

                referenceMatches = false;
                break;
            }
        }

        if (amountMatches &&
                referenceMatches &&
                currencyMatches) {

            return new MatchingResult(
                    "MATCHED",
                    String.join(",", gatewayTransactionIds),
                    bankTransactionId,
                    "MANY_TO_1",
                    0.0,
                    0,
                    null
            );
        }

        StringBuilder discrepancyDetails = new StringBuilder();

        if (!referenceMatches) {
            discrepancyDetails.append("Reference mismatch");
        }

        if (!amountMatches) {
            if (discrepancyDetails.length() > 0) {
                discrepancyDetails.append("; ");
            }

            discrepancyDetails.append("Total transaction amount mismatch");
        }

        if (!currencyMatches) {
            if (discrepancyDetails.length() > 0) {
                discrepancyDetails.append("; ");
            }

            discrepancyDetails.append("Currency mismatch");
        }

        return new MatchingResult(
                "UNMATCHED",
                String.join(",", gatewayTransactionIds),
                bankTransactionId,
                "MANY_TO_1",
                amountDifference,
                0,
                discrepancyDetails.toString()
        );
    }
}