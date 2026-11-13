package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

public class PartialAmountMatchingService {

    private static final double ALLOWED_DIFFERENCE = 50.00;

    public MatchingResult match(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency) {

        boolean referenceMatches =
                gatewayReferenceId != null &&
                gatewayReferenceId.equals(bankReferenceId);

        boolean currencyMatches =
                gatewayCurrency != null &&
                gatewayCurrency.equals(bankCurrency);

        double amountDifference =
                gatewayAmount - bankAmount;

        boolean amountWithinAllowedDifference =
                Math.abs(amountDifference) <= ALLOWED_DIFFERENCE;

        if (referenceMatches &&
                currencyMatches &&
                amountWithinAllowedDifference) {

            return new MatchingResult(
                    "MATCHED_WITH_DIFFERENCE",
                    gatewayTransactionId,
                    bankTransactionId,
                    "PARTIAL_AMOUNT",
                    amountDifference,
                    0,
                    "Amount difference within allowed threshold"
            );
        }

        StringBuilder discrepancyDetails = new StringBuilder();

        if (!referenceMatches) {
            discrepancyDetails.append("Reference mismatch");
        }

        if (!amountWithinAllowedDifference) {
            if (discrepancyDetails.length() > 0) {
                discrepancyDetails.append("; ");
            }

            discrepancyDetails.append(
                    "Amount difference exceeds allowed threshold"
            );
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
                bankTransactionId,
                "PARTIAL_AMOUNT",
                amountDifference,
                0,
                discrepancyDetails.toString()
        );
    }
}