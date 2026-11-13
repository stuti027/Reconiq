package com.reconiq.normalization.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AmountParserTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static AmountResult parseNumber(String text) {
        return AmountParser.parse(JSON.numberNode(new BigDecimal(text)));
    }

    @Test
    void acceptsTwoDecimals() {
        AmountResult result = parseNumber("1051.32");
        assertThat(result.isValid()).isTrue();
        assertThat(result.value()).isEqualTo(new BigDecimal("1051.32"));
    }

    @Test
    void alwaysNormalizesToExactlyTwoDecimals() {
        assertThat(parseNumber("1051.3").value()).isEqualTo(new BigDecimal("1051.30"));
        assertThat(parseNumber("1051").value()).isEqualTo(new BigDecimal("1051.00"));
        assertThat(parseNumber("1051.3").value().scale()).isEqualTo(2);
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimals() {
        assertThat(parseNumber("1051.300").value()).isEqualTo(new BigDecimal("1051.30"));
    }

    @Test
    void acceptsNegativeAmounts() {
        assertThat(parseNumber("-5.50").value()).isEqualTo(new BigDecimal("-5.50"));
    }

    @Test
    void acceptsSixteenIntegerDigits() {
        assertThat(parseNumber("1234567890123456.78").isValid()).isTrue();
    }

    @Test
    void rejectsMoreThanTwoRealDecimals() {
        AmountResult result = parseNumber("1051.305");
        assertThat(result.isValid()).isFalse();
        assertThat(result.error()).isEqualTo(RejectReason.INVALID_AMOUNT_SCALE);
    }

    @Test
    void rejectsMoreThanSixteenIntegerDigits() {
        AmountResult result = parseNumber("12345678901234567.00");
        assertThat(result.error()).isEqualTo(RejectReason.INVALID_AMOUNT_SCALE);
    }

    @Test
    void missingOrNullAmountIsNotAnError() {
        for (var node : new com.fasterxml.jackson.databind.JsonNode[] {
                null, JSON.nullNode(), com.fasterxml.jackson.databind.node.MissingNode.getInstance()}) {
            AmountResult result = AmountParser.parse(node);
            assertThat(result.isValid()).isTrue();
            assertThat(result.value()).isNull();
        }
    }

    @Test
    void rejectsNonNumericValues() {
        assertThat(AmountParser.parse(JSON.textNode("abc")).error()).isEqualTo(RejectReason.INVALID_AMOUNT);
        assertThat(AmountParser.parse(JSON.textNode("1051.32")).error()).isEqualTo(RejectReason.INVALID_AMOUNT);
        assertThat(AmountParser.parse(JSON.booleanNode(true)).error()).isEqualTo(RejectReason.INVALID_AMOUNT);
    }
}