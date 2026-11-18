package com.reconiq.resolution.engine.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record EvidenceReference(
        @JsonProperty("kind") Kind kind,
        @JsonProperty("ref_id") String refId) {

    public EvidenceReference {
        Objects.requireNonNull(kind, "kind must not be null");
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException("ref_id must not be blank");
        }
    }

    public enum Kind {
        TRANSACTION("transaction"),
        PAST_CASE("past_case");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Kind fromValue(String value) {
            for (Kind kind : values()) {
                if (kind.value.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unsupported evidence kind: " + value);
        }
    }
}
