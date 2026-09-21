package com.reconiq.normalization.input;

/** Why an input message was rejected before normalization. */
public enum RejectReason {
    INVALID_JSON,
    MISSING_EVENT_ID,
    INVALID_TIMESTAMP,
    UNKNOWN_SOURCE,
    MISSING_TRANSACTION,
    MISSING_TRANSACTION_ID,
    INVALID_AMOUNT,
    INVALID_AMOUNT_SCALE
}