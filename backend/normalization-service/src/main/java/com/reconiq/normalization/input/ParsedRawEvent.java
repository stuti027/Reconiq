package com.reconiq.normalization.input;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A validated input event. Values are as received; normalization rules are applied later.
 * eventId, transactionId, source and eventTimestamp are always present; all other fields may be null.
 */
public record ParsedRawEvent(
        String eventId,
        String transactionId,
        String source,
        Instant eventTimestamp,
        String referenceId,
        String merchantId,
        String customerId,
        String transactionType,
        String settlementId,
        BigDecimal amountValue,
        String amountCurrency,
        String status,
        String description) implements ParseResult {
}