package com.reconiq.normalization.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimestampParserTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void parsesEpochSecondsWithNanosecondFraction() {
        var node = JSON.numberNode(new BigDecimal("1787219280.000000000"));
        assertThat(TimestampParser.parse(node)).contains(Instant.parse("2026-08-20T09:48:00Z"));
    }

    @Test
    void parsesEpochMilliseconds() {
        var node = JSON.numberNode(1787219280123L);
        assertThat(TimestampParser.parse(node)).contains(Instant.parse("2026-08-20T09:48:00.123Z"));
    }

    @Test
    void parsesIsoStringInUtc() {
        var node = JSON.textNode("2026-08-20T09:48:00Z");
        assertThat(TimestampParser.parse(node)).contains(Instant.parse("2026-08-20T09:48:00Z"));
    }

    @Test
    void parsesIsoStringWithOffset() {
        var node = JSON.textNode("2026-08-20T15:18:00+05:30");
        assertThat(TimestampParser.parse(node)).contains(Instant.parse("2026-08-20T09:48:00Z"));
    }

    @Test
    void truncatesToMilliseconds() {
        var node = JSON.numberNode(new BigDecimal("1787219280.123456789"));
        assertThat(TimestampParser.parse(node)).contains(Instant.parse("2026-08-20T09:48:00.123Z"));
    }

    @Test
    void rejectsNumbersOutsideBothRanges() {
        assertThat(TimestampParser.parse(JSON.numberNode(12345L))).isEmpty();
        assertThat(TimestampParser.parse(JSON.numberNode(1_000_000_000_000_000L))).isEmpty();
    }

    @Test
    void rejectsUnparseableText() {
        assertThat(TimestampParser.parse(JSON.textNode("yesterday"))).isEmpty();
        assertThat(TimestampParser.parse(JSON.textNode(""))).isEmpty();
    }

    @Test
    void rejectsMissingNullAndWrongTypes() {
        assertThat(TimestampParser.parse(null)).isEmpty();
        assertThat(TimestampParser.parse(JSON.nullNode())).isEmpty();
        assertThat(TimestampParser.parse(JSON.booleanNode(true))).isEmpty();
    }
}