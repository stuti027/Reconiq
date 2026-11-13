package com.reconiq.normalization.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The real status-mapping.csv must contain only the four allowed canonical_status values (the DB CHECK
 * constraint and the Avro schema both assume this). StatusMapping.fromClasspath already enforces this at
 * load time; this test makes the guarantee explicit and readable on its own.
 */
class StatusMappingCsvContentTest {

    private static final Set<String> ALLOWED = Set.of("COMPLETED", "FAILED", "PENDING", "UNKNOWN");

    @Test
    void everyRowsCanonicalStatusIsOneOfTheFourAllowedValues() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("status-mapping.csv")) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String header = reader.readLine();
            assertThat(header.strip()).isEqualTo("source,raw_status,canonical_status");

            String line;
            int rows = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                assertThat(parts).hasSize(3);
                String canonicalStatus = parts[2].strip().toUpperCase(java.util.Locale.ROOT);
                assertThat(ALLOWED).as("line: %s", line).contains(canonicalStatus);
                rows++;
            }
            assertThat(rows).isGreaterThan(0);
        }
    }
}
