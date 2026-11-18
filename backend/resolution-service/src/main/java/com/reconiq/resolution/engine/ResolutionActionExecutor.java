package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.ResolutionAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

@Component
public class ResolutionActionExecutor {

    public ActionExecutionSummary execute(String caseId, ResolutionAction action, BigDecimal discrepancyAmount) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(discrepancyAmount, "discrepancyAmount must not be null");

        String summary = switch (action) {
            case FEE_WRITE_OFF -> "Fee write-off dispatched";
            case LEDGER_ADJUSTMENT -> "Ledger adjustment dispatched";
            case RETRY_SETTLEMENT -> "Settlement retry dispatched";
            case MANUAL_ESCALATION -> "Manual review escalation dispatched";
        };
        return new ActionExecutionSummary(
                caseId,
                action,
                ActionExecutionSummary.Status.DISPATCHED,
                discrepancyAmount,
                summary);
    }
}
