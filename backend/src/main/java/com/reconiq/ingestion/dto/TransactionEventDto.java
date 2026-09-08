package com.reconiq.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionEventDto(
    @JsonProperty("event_id") String eventId,
    @JsonProperty("event_type") String eventType,
    String source,
    @JsonProperty("event_timestamp") Instant eventTimestamp,
    EventData data
) {
    public record EventData(Transaction transaction) {}

    public record Transaction(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("reference_id") String referenceId,
        Amount amount,
        Instant timestamp,
        String status,
        @JsonProperty("merchant_id") String merchantId,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("transaction_type") String transactionType,
        @JsonProperty("settlement_id") String settlementId,
        String description
    ) {}

    public record Amount(
        BigDecimal value,
        String currency
    ) {}
}