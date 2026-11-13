package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

public class OneToOneMatchingService implements MatchingService {

    @Override
    public MatchingResult match(
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

        boolean referenceMatches =
                gatewayReferenceId != null &&
                gatewayReferenceId.equals(bankReferenceId);

        boolean amountMatches =
                gatewayAmount == bankAmount;

        boolean currencyMatches =
                gatewayCurrency != null &&
                gatewayCurrency.equals(bankCurrency);

        if (referenceMatches && amountMatches && currencyMatches) {
            return new MatchingResult(
                    "MATCHED",
                    gatewayTransactionId,
                    bankTransactionId,
                    "1_TO_1",
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
            discrepancyDetails.append("Amount mismatch");
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
                "1_TO_1",
                gatewayAmount - bankAmount,
                0,
                discrepancyDetails.toString()
        );
    }
}