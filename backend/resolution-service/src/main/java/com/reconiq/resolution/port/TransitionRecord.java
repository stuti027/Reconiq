package com.reconiq.resolution.port;

import java.time.Instant;
import java.util.Objects;

public record TransitionRecord(
        String caseId,
        CaseState fromState,
        CaseState toState,
        ResolutionAction action,
        String actor,
        String reason,
        Instant transitionedAt) {

    public TransitionRecord(
            String caseId,
            CaseState fromState,
            CaseState toState,
            ResolutionAction action,
            String reason,
            Instant transitionedAt) {
        this(caseId, fromState, toState, action, null, reason, transitionedAt);
    }

    public TransitionRecord {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(toState, "toState must not be null");
        Objects.requireNonNull(transitionedAt, "transitionedAt must not be null");
    }
}
