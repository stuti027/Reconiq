package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.CaseState;

import java.math.BigDecimal;
import java.util.Objects;

public record ResolutionRuleResult(
        String caseId,
        CaseState finalState,
        boolean skipped,
        BigDecimal discrepancyAmount,
        ActionExecutionSummary actionExecution,
        String reason) {

    public ResolutionRuleResult {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (!skipped) {
            Objects.requireNonNull(discrepancyAmount, "discrepancyAmount must not be null");
            Objects.requireNonNull(actionExecution, "actionExecution must not be null");
        }
    }
}
