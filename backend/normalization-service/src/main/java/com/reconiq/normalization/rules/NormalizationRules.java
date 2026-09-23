package com.reconiq.normalization.rules;

import java.util.Locale;
import java.util.regex.Pattern;

/** Field-level normalization rules applied when building a canonical transaction. */
public final class NormalizationRules {

    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^A-Z0-9]");

    private NormalizationRules() {
    }

    /** Trims the value; a null or blank value becomes null. The case is never changed. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** Like {@link #clean}, then upper-cases. For enum-like fields such as source, type and currency. */
    public static String cleanUpper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
    }

    /**
     * Upper-cases the reference and removes every character that is not a letter or digit (A-Z, 0-9).
     * Returns null when nothing remains. Meant for fuzzy matching only; the raw reference is kept separately.
     */
    public static String referenceKey(String referenceId) {
        String upper = cleanUpper(referenceId);
        if (upper == null) {
            return null;
        }
        String key = NOT_LETTER_OR_DIGIT.matcher(upper).replaceAll("");
        return key.isEmpty() ? null : key;
    }
}