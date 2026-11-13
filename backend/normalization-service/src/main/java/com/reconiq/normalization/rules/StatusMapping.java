package com.reconiq.normalization.rules;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a source system's raw status to the canonical status, using a CSV file with the columns
 * source, raw_status, canonical_status. The file is validated strictly when it is loaded.
 */
public final class StatusMapping {

    public static final String UNKNOWN = "UNKNOWN";

    private static final String HEADER = "source,raw_status,canonical_status";
    private static final Set<String> ALLOWED = Set.of("COMPLETED", "FAILED", "PENDING", UNKNOWN);

    private record Key(String source, String rawStatus) {
    }

    private final Map<Key, String> mappings;

    private StatusMapping(Map<Key, String> mappings) {
        this.mappings = Map.copyOf(mappings);
    }

    public static StatusMapping fromClasspath(String resource) {
        InputStream stream = StatusMapping.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Status mapping file not found on the classpath: " + resource);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return fromReader(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read status mapping file: " + resource, e);
        }
    }

    public static StatusMapping fromReader(Reader reader) {
        BufferedReader lines = new BufferedReader(reader);
        try {
            String header = lines.readLine();
            if (header == null || !HEADER.equals(header.strip())) {
                throw new IllegalStateException("Status mapping: line 1 must be the header '" + HEADER + "'");
            }
            Map<Key, String> mappings = new HashMap<>();
            int lineNumber = 1;
            String line;
            while ((line = lines.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                if (parts.length != 3) {
                    throw invalid(lineNumber, "expected 3 columns but found " + parts.length);
                }
                String source = NormalizationRules.cleanUpper(parts[0]);
                String rawStatus = NormalizationRules.cleanUpper(parts[1]);
                String canonical = NormalizationRules.cleanUpper(parts[2]);
                if (source == null || rawStatus == null || canonical == null) {
                    throw invalid(lineNumber, "values must not be blank");
                }
                if (!ALLOWED.contains(canonical)) {
                    throw invalid(lineNumber, "canonical_status '" + canonical + "' is not one of " + ALLOWED);
                }
                if (mappings.put(new Key(source, rawStatus), canonical) != null) {
                    throw invalid(lineNumber, "duplicate key (" + source + ", " + rawStatus + ")");
                }
            }
            return new StatusMapping(mappings);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the status mapping", e);
        }
    }

    /** The canonical status for the pair, or empty when there is no mapping (including null or blank input). */
    public Optional<String> lookup(String source, String rawStatus) {
        String normalizedSource = NormalizationRules.cleanUpper(source);
        String normalizedStatus = NormalizationRules.cleanUpper(rawStatus);
        if (normalizedSource == null || normalizedStatus == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mappings.get(new Key(normalizedSource, normalizedStatus)));
    }

    /** The mapped canonical status, or UNKNOWN when there is no mapping. */
    public String canonicalStatus(String source, String rawStatus) {
        return lookup(source, rawStatus).orElse(UNKNOWN);
    }

    private static IllegalStateException invalid(int lineNumber, String message) {
        return new IllegalStateException("Status mapping line " + lineNumber + ": " + message);
    }
}