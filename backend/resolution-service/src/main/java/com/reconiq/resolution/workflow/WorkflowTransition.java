package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;

import java.util.List;

public record WorkflowTransition(
        CaseState targetState,
        String rootCause,
        List<String> citedEvidence,
        String actor,
        String reason,
        ResolutionAction action) {

    public WorkflowTransition {
        citedEvidence = citedEvidence == null ? List.of() : List.copyOf(citedEvidence);
    }
}
