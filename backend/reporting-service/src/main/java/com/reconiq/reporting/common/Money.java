package com.reconiq.reporting.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Contract 1.1: {"value": "1051.32", "currency": "INR"}. The value is a string with 2 decimals,
 * because a JSON number would be read as a double by the browser and can lose precision.
 */
public record Money(String value, String currency) {

    /** Returns null when the source row has no amount (Phase 1 allows a null amount_value). */
    public static Money of(BigDecimal amount, String currency) {
        if (amount == null) {
            return null;
        }
        return new Money(amount.setScale(2, RoundingMode.HALF_UP).toPlainString(), currency);
    }
}
