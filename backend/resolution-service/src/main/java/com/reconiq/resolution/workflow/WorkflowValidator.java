package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CaseState;

public final class WorkflowValidator {

    public void validate(WorkflowTransition transition) {
        if (transition == null) {
            throw new WorkflowValidationException("Transition details must not be null");
        }

        if (transition.targetState() == CaseState.AUTO_RESOLVED) {
            requireNonBlank(transition.rootCause(), "Root cause is required for AUTO_RESOLVED");
            if (transition.citedEvidence().stream().noneMatch(WorkflowValidator::isNonBlank)) {
                throw new WorkflowValidationException("Cited evidence is required for AUTO_RESOLVED");
            }
        }

        if (transition.targetState() == CaseState.APPROVED
                || transition.targetState() == CaseState.REJECTED) {
            requireNonBlank(transition.actor(), "Actor is required for " + transition.targetState());
            requireNonBlank(transition.reason(), "Reason is required for " + transition.targetState());
        }
    }

    private static void requireNonBlank(String value, String message) {
        if (!isNonBlank(value)) {
            throw new WorkflowValidationException(message);
        }
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
