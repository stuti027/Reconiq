package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

public class DuplicateDetectionService {

    public MatchingResult checkDuplicate(
            String transactionId,
            String referenceId,
            String existingTransactionId,
            String existingReferenceId) {

        boolean transactionIdMatches =
                transactionId != null &&
                transactionId.equals(existingTransactionId);

        boolean referenceIdMatches =
                referenceId != null &&
                referenceId.equals(existingReferenceId);

        if (transactionIdMatches || referenceIdMatches) {

            StringBuilder details = new StringBuilder();

            if (transactionIdMatches) {
                details.append("Duplicate transaction ID");
            }

            if (referenceIdMatches) {
                if (details.length() > 0) {
                    details.append("; ");
                }
                details.append("Duplicate reference ID");
            }

            return new MatchingResult(
                    "DUPLICATE",
                    transactionId,
                    existingTransactionId,
                    "DUPLICATE",
                    0.0,
                    0,
                    details.toString()
            );
        }

        return new MatchingResult(
                "UNMATCHED",
                transactionId,
                existingTransactionId,
                "DUPLICATE",
                0.0,
                0,
                "No duplicate detected"
        );
    }
}