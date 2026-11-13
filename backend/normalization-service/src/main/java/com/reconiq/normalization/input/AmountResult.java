package com.reconiq.normalization.input;

import java.math.BigDecimal;

/**
 * Outcome of parsing an amount: a value (null when the source sent no amount) or an error reason.
 */
public record AmountResult(BigDecimal value, RejectReason error) {

    public static AmountResult absent() {
        return new AmountResult(null, null);
    }

    public static AmountResult of(BigDecimal value) {
        return new AmountResult(value, null);
    }

    public static AmountResult invalid(RejectReason error) {
        return new AmountResult(null, error);
    }

    public boolean isValid() {
        return error == null;
    }
}