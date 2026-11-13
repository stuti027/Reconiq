package com.reconiq.normalization.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

class StatusMappingTest {

    private static final String HEADER = "source,raw_status,canonical_status\n";

    private static final String VALID = HEADER
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    private static StatusMapping load(String csv) {
        return StatusMapping.fromReader(new StringReader(csv));
    }

    @Test
    void looksUpMappedStatuses() {
        StatusMapping mapping = load(VALID);
        assertThat(mapping.lookup("PAYMENT_GATEWAY", "SUCCESS")).contains("COMPLETED");
        assertThat(mapping.lookup("PAYMENT_GATEWAY", "FAILED")).contains("FAILED");
        assertThat(mapping.lookup("BANK", "SETTLED")).contains("COMPLETED");
        assertThat(mapping.lookup("BANK", "FAILED")).contains("FAILED");
    }

    @Test
    void lookupIgnoresCaseAndSurroundingSpaces() {
        assertThat(load(VALID).lookup(" bank ", " settled ")).contains("COMPLETED");
    }

    @Test
    void sourceIsPartOfTheKey() {
        assertThat(load(VALID).lookup("BANK", "SUCCESS")).isEmpty();
    }

    @Test
    void unmappedOrMissingStatusGivesEmptyLookupAndUnknownStatus() {
        StatusMapping mapping = load(VALID);
        assertThat(mapping.lookup("BANK", "WEIRD")).isEmpty();
        assertThat(mapping.canonicalStatus("BANK", "WEIRD")).isEqualTo("UNKNOWN");
        assertThat(mapping.canonicalStatus("BANK", null)).isEqualTo("UNKNOWN");
        assertThat(mapping.canonicalStatus("BANK", "  ")).isEqualTo("UNKNOWN");
        assertThat(mapping.canonicalStatus(null, "SETTLED")).isEqualTo("UNKNOWN");
    }

    @Test
    void canonicalStatusReturnsTheMappedValue() {
        assertThat(load(VALID).canonicalStatus("BANK", "SETTLED")).isEqualTo("COMPLETED");
    }

    @Test
    void toleratesWindowsLineEndingsAndBlankLines() {
        String csv = "source,raw_status,canonical_status\r\n\r\nBANK,SETTLED,COMPLETED\r\n\r\n";
        assertThat(load(csv).lookup("BANK", "SETTLED")).contains("COMPLETED");
    }

    @Test
    void rejectsWrongHeader() {
        assertThatThrownBy(() -> load("source,status,canonical\nBANK,SETTLED,COMPLETED\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header");
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> load(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsRowWithWrongNumberOfColumns() {
        assertThatThrownBy(() -> load(HEADER + "BANK,SETTLED\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void rejectsBlankValues() {
        assertThatThrownBy(() -> load(HEADER + "BANK,,COMPLETED\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    void rejectsCanonicalValueOutsideTheAllowedSet() {
        assertThatThrownBy(() -> load(HEADER + "BANK,SETTLED,DONE\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DONE");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> load(HEADER + "BANK,SETTLED,COMPLETED\nbank, settled ,FAILED\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void loadsTheRealMappingFileFromTheClasspath() {
        StatusMapping mapping = StatusMapping.fromClasspath("status-mapping.csv");
        assertThat(mapping.lookup("PAYMENT_GATEWAY", "SUCCESS")).contains("COMPLETED");
        assertThat(mapping.lookup("PAYMENT_GATEWAY", "FAILED")).contains("FAILED");
        assertThat(mapping.lookup("BANK", "SETTLED")).contains("COMPLETED");
        assertThat(mapping.lookup("BANK", "FAILED")).contains("FAILED");
    }

    @Test
    void missingResourceFailsWithAClearMessage() {
        assertThatThrownBy(() -> StatusMapping.fromClasspath("no-such-file.csv"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-file.csv");
    }
}