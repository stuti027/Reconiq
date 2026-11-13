package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

import java.util.List;

public class OneToManyMatchingService {

    public MatchingResult match(
            String gatewayTransactionId,
            String gatewayReferenceId,
            double gatewayAmount,
            String gatewayCurrency,
            List<String> bankTransactionIds,
            List<String> bankReferenceIds,
            List<Double> bankAmounts,
            String bankCurrency) {

        if (bankTransactionIds == null ||
                bankReferenceIds == null ||
                bankAmounts == null ||
                bankTransactionIds.isEmpty()) {

            return new MatchingResult(
                    "UNMATCHED",
                    gatewayTransactionId,
                    null,
                    "1_TO_MANY",
                    gatewayAmount,
                    0,
                    "No bank transactions provided"
            );
        }

        if (bankTransactionIds.size() != bankAmounts.size() ||
                bankTransactionIds.size() != bankReferenceIds.size()) {

            return new MatchingResult(
                    "UNMATCHED",
                    gatewayTransactionId,
                    null,
                    "1_TO_MANY",
                    gatewayAmount,
                    0,
                    "Bank transaction data is inconsistent"
            );
        }

        boolean currencyMatches =
                gatewayCurrency != null &&
                gatewayCurrency.equals(bankCurrency);

        double totalBankAmount = 0.0;

        for (double amount : bankAmounts) {
            totalBankAmount += amount;
        }

        double amountDifference =
                gatewayAmount - totalBankAmount;

        boolean amountMatches =
                gatewayAmount == totalBankAmount;

        boolean referenceMatches = true;

        for (String referenceId : bankReferenceIds) {
            if (gatewayReferenceId == null ||
                    !gatewayReferenceId.equals(referenceId)) {

                referenceMatches = false;
                break;
            }
        }

        if (amountMatches &&
                referenceMatches &&
                currencyMatches) {

            return new MatchingResult(
                    "MATCHED",
                    gatewayTransactionId,
                    String.join(",", bankTransactionIds),
                    "1_TO_MANY",
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

            discrepancyDetails.append("Total settlement amount mismatch");
        }

        if (!currencyMatches) {
            if (discrepancyDetails.length() > 0) {
                discrepancyDetails.append("; ");
            }

            discrepancyDetails.append("Currency mismatch");
        }

        return new MatchingResult(
                "UNMATCHED",
                gatewayTransactionId,
                String.join(",", bankTransactionIds),
                "1_TO_MANY",
                amountDifference,
                0,
                discrepancyDetails.toString()
        );
    }
}