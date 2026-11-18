package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.ResolutionAction;

import java.math.BigDecimal;
import java.util.Objects;

public record ActionExecutionSummary(
        String caseId,
        ResolutionAction action,
        Status status,
        BigDecimal discrepancyAmount,
        String summary) {

    public ActionExecutionSummary {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(discrepancyAmount, "discrepancyAmount must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
    }

    public enum Status {
        DISPATCHED
    }
}
