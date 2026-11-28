package com.reconiq.reporting.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A field typed {@code T | Unavailable} in the contract (1.3).
 * Serializes as the plain value, or as {"available": false, "reason": "..."}; never as 0 or null.
 */
public final class Available<T> {

    private final T value;
    private final UnavailableReason reason;

    private Available(T value, UnavailableReason reason) {
        this.value = value;
        this.reason = reason;
    }

    public static <T> Available<T> of(T value) {
        return new Available<>(Objects.requireNonNull(value, "use unavailable(reason) instead of null"), null);
    }

    public static <T> Available<T> unavailable(UnavailableReason reason) {
        return new Available<>(null, Objects.requireNonNull(reason));
    }

    public boolean isAvailable() {
        return reason == null;
    }

    @JsonValue
    Object json() {
        if (isAvailable()) {
            return value;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", false);
        body.put("reason", reason.name());
        return body;
    }
}
