package com.reconiq.resolution.api.dto;

import com.reconiq.resolution.port.ResolutionAction;
import jakarta.validation.constraints.NotBlank;

public record ReviewerDecisionPayload(
        @NotBlank String reviewerId,
        ResolutionAction action,
        String notes) {
}
