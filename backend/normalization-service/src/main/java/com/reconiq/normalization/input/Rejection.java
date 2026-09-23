package com.reconiq.normalization.input;

/**
 * An input message that could not be accepted.
 *
 * @param reason       machine-readable reason code
 * @param detail       short human-readable explanation
 * @param originalText the message value exactly as received (may be null)
 */
public record Rejection(RejectReason reason, String detail, String originalText) implements ParseResult {
}