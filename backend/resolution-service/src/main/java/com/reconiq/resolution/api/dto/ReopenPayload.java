package com.reconiq.resolution.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReopenPayload(
        @NotBlank String actor,
        @NotBlank String reason) {
}
