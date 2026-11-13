package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

import java.time.Duration;
import java.time.Instant;

public class TimeWindowMatchingService implements MatchingService {

    private static final long TIME_WINDOW_SECONDS = 300;

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

        long timeDifferenceSeconds;

        try {
            Instant gatewayTime = Instant.parse(gatewayTimestamp);
            Instant bankTime = Instant.parse(bankTimestamp);

            timeDifferenceSeconds =
                    Math.abs(Duration.between(gatewayTime, bankTime).getSeconds());

        } catch (Exception e) {
            return new MatchingResult(
                    "UNMATCHED",
                    gatewayTransactionId,
                    bankTransactionId,
                    "TIME_WINDOW",
                    gatewayAmount - bankAmount,
                    0,
                    "Invalid timestamp"
            );
        }

        boolean timestampMatches =
                timeDifferenceSeconds <= TIME_WINDOW_SECONDS;

        if (referenceMatches &&
                amountMatches &&
                currencyMatches &&
                timestampMatches) {

            return new MatchingResult(
                    "MATCHED",
                    gatewayTransactionId,
                    bankTransactionId,
                    "TIME_WINDOW",
                    0.0,
                    timeDifferenceSeconds,
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

        if (!timestampMatches) {
            if (discrepancyDetails.length() > 0) {
                discrepancyDetails.append("; ");
            }
            discrepancyDetails.append("Timestamp outside 5-minute window");
        }

        return new MatchingResult(
                "UNMATCHED",
                gatewayTransactionId,
                bankTransactionId,
                "TIME_WINDOW",
                gatewayAmount - bankAmount,
                timeDifferenceSeconds,
                discrepancyDetails.toString()
        );
    }
}