package com.reconiq.resolution.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewerRejectionPayload(
        @NotBlank String reviewerId,
        @NotBlank String notes,
        boolean closeImmediately) {
}
