package com.reconiq.normalization.input;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Parses the amount value into an exact decimal with exactly two decimal places. */
public final class AmountParser {

    private static final int SCALE = 2;
    private static final int MAX_INTEGER_DIGITS = 16;

    private AmountParser() {
    }

    public static AmountResult parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return AmountResult.absent();
        }
        if (!node.isNumber()) {
            return AmountResult.invalid(RejectReason.INVALID_AMOUNT);
        }
        BigDecimal value = node.decimalValue();
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > SCALE) {
            return AmountResult.invalid(RejectReason.INVALID_AMOUNT_SCALE);
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
            return AmountResult.invalid(RejectReason.INVALID_AMOUNT_SCALE);
        }
        return AmountResult.of(value.setScale(SCALE, RoundingMode.UNNECESSARY));
    }
}