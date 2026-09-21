package com.reconiq.normalization.input;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Converts a JSON timestamp (epoch number or ISO-8601 text) into an Instant with millisecond precision. */
public final class TimestampParser {

    private static final BigDecimal SECONDS_MIN = new BigDecimal("9.5E8");
    private static final BigDecimal SECONDS_MAX = new BigDecimal("4.1E9");
    private static final BigDecimal MILLIS_MIN = new BigDecimal("9.5E11");
    private static final BigDecimal MILLIS_MAX = new BigDecimal("4.1E12");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private TimestampParser() {
    }

    /** Returns the instant, or empty if the value is missing, of the wrong type, out of range, or unparseable. */
    public static Optional<Instant> parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isNumber()) {
            return fromNumber(node.decimalValue());
        }
        if (node.isTextual()) {
            return fromText(node.asText());
        }
        return Optional.empty();
    }

    private static Optional<Instant> fromNumber(BigDecimal value) {
        BigDecimal millis;
        if (inRange(value, SECONDS_MIN, SECONDS_MAX)) {
            millis = value.multiply(THOUSAND);
        } else if (inRange(value, MILLIS_MIN, MILLIS_MAX)) {
            millis = value;
        } else {
            return Optional.empty();
        }
        long epochMillis = millis.setScale(0, RoundingMode.FLOOR).longValueExact();
        return Optional.of(Instant.ofEpochMilli(epochMillis));
    }

    private static Optional<Instant> fromText(String text) {
        try {
            return Optional.of(OffsetDateTime.parse(text.trim()).toInstant().truncatedTo(ChronoUnit.MILLIS));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}