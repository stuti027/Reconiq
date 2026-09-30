package com.reconciliation.model;

public class MatchingResult {

    private String status;
    private String gatewayTransactionId;
    private String bankTransactionId;
    private String matchingStrategy;
    private double amountDifference;
    private long timeDifferenceSeconds;
    private String discrepancyDetails;

    public MatchingResult() {
    }

    public MatchingResult(
            String status,
            String gatewayTransactionId,
            String bankTransactionId,
            String matchingStrategy,
            double amountDifference,
            long timeDifferenceSeconds,
            String discrepancyDetails) {

        this.status = status;
        this.gatewayTransactionId = gatewayTransactionId;
        this.bankTransactionId = bankTransactionId;
        this.matchingStrategy = matchingStrategy;
        this.amountDifference = amountDifference;
        this.timeDifferenceSeconds = timeDifferenceSeconds;
        this.discrepancyDetails = discrepancyDetails;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getGatewayTransactionId() {
        return gatewayTransactionId;
    }

    public void setGatewayTransactionId(String gatewayTransactionId) {
        this.gatewayTransactionId = gatewayTransactionId;
    }

    public String getBankTransactionId() {
        return bankTransactionId;
    }

    public void setBankTransactionId(String bankTransactionId) {
        this.bankTransactionId = bankTransactionId;
    }

    public String getMatchingStrategy() {
        return matchingStrategy;
    }

    public void setMatchingStrategy(String matchingStrategy) {
        this.matchingStrategy = matchingStrategy;
    }

    public double getAmountDifference() {
        return amountDifference;
    }

    public void setAmountDifference(double amountDifference) {
        this.amountDifference = amountDifference;
    }

    public long getTimeDifferenceSeconds() {
        return timeDifferenceSeconds;
    }

    public void setTimeDifferenceSeconds(long timeDifferenceSeconds) {
        this.timeDifferenceSeconds = timeDifferenceSeconds;
    }

    public String getDiscrepancyDetails() {
        return discrepancyDetails;
    }

    public void setDiscrepancyDetails(String discrepancyDetails) {
        this.discrepancyDetails = discrepancyDetails;
    }
}