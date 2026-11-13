package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

public class FuzzyMatchingService {

    private static final double SIMILARITY_THRESHOLD = 0.80;

    public MatchingResult match(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency) {

        double referenceSimilarity =
                calculateSimilarity(gatewayReferenceId, bankReferenceId);

        boolean amountMatches =
                gatewayAmount == bankAmount;

        boolean currencyMatches =
                gatewayCurrency != null &&
                gatewayCurrency.equals(bankCurrency);

        boolean referenceMatches =
                referenceSimilarity >= SIMILARITY_THRESHOLD;

        if (referenceMatches &&
                amountMatches &&
                currencyMatches) {

            return new MatchingResult(
                    "MATCHED",
                    gatewayTransactionId,
                    bankTransactionId,
                    "FUZZY",
                    0.0,
                    0,
                    "Reference similarity: " +
                            String.format("%.2f", referenceSimilarity)
            );
        }

        StringBuilder discrepancyDetails = new StringBuilder();

        if (!referenceMatches) {
            discrepancyDetails.append(
                    "Reference similarity below threshold"
            );
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
                "FUZZY",
                gatewayAmount - bankAmount,
                0,
                discrepancyDetails.toString()
        );
    }

    private double calculateSimilarity(String first, String second) {

        if (first == null || second == null) {
            return 0.0;
        }

        if (first.equals(second)) {
            return 1.0;
        }

        int distance = levenshteinDistance(first, second);
        int maxLength = Math.max(first.length(), second.length());

        if (maxLength == 0) {
            return 1.0;
        }

        return 1.0 - ((double) distance / maxLength);
    }

    private int levenshteinDistance(String first, String second) {

        int[][] matrix =
                new int[first.length() + 1][second.length() + 1];

        for (int i = 0; i <= first.length(); i++) {
            matrix[i][0] = i;
        }

        for (int j = 0; j <= second.length(); j++) {
            matrix[0][j] = j;
        }

        for (int i = 1; i <= first.length(); i++) {

            for (int j = 1; j <= second.length(); j++) {

                int cost =
                        first.charAt(i - 1) == second.charAt(j - 1)
                                ? 0
                                : 1;

                matrix[i][j] = Math.min(
                        Math.min(
                                matrix[i - 1][j] + 1,
                                matrix[i][j - 1] + 1
                        ),
                        matrix[i - 1][j - 1] + cost
                );
            }
        }

        return matrix[first.length()][second.length()];
    }
}