package com.reconiq.normalization.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NormalizationRulesTest {

    @Test
    void cleanTrimsAndTurnsBlankIntoNull() {
        assertThat(NormalizationRules.clean("  MER001 ")).isEqualTo("MER001");
        assertThat(NormalizationRules.clean("a b")).isEqualTo("a b");
        assertThat(NormalizationRules.clean("")).isNull();
        assertThat(NormalizationRules.clean("   ")).isNull();
        assertThat(NormalizationRules.clean(null)).isNull();
    }

    @Test
    void cleanNeverChangesCase() {
        assertThat(NormalizationRules.clean("Txn-a1")).isEqualTo("Txn-a1");
    }

    @Test
    void cleanUpperTrimsAndUpperCases() {
        assertThat(NormalizationRules.cleanUpper(" inr ")).isEqualTo("INR");
        assertThat(NormalizationRules.cleanUpper("payment")).isEqualTo("PAYMENT");
        assertThat(NormalizationRules.cleanUpper("  ")).isNull();
        assertThat(NormalizationRules.cleanUpper(null)).isNull();
    }

    @Test
    void referenceKeyUpperCasesAndDropsEverythingButLettersAndDigits() {
        assertThat(NormalizationRules.referenceKey("PAY-20260822-77881")).isEqualTo("PAY2026082277881");
        assertThat(NormalizationRules.referenceKey("  ref 001/a ")).isEqualTo("REF001A");
        assertThat(NormalizationRules.referenceKey("abc-1")).isEqualTo("ABC1");
    }

    @Test
    void referenceKeyIsNullWhenNothingRemains() {
        assertThat(NormalizationRules.referenceKey("---")).isNull();
        assertThat(NormalizationRules.referenceKey("")).isNull();
        assertThat(NormalizationRules.referenceKey("   ")).isNull();
        assertThat(NormalizationRules.referenceKey(null)).isNull();
    }

    @Test
    void referenceKeyCollapsesReferencesThatDifferOnlyByPunctuation() {
        // Known, accepted limit: the key is only meant for fuzzy matching, the raw reference is kept as well.
        assertThat(NormalizationRules.referenceKey("PAY-001"))
                .isEqualTo(NormalizationRules.referenceKey("PAY 001"));
    }
}