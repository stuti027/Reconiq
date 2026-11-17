package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CaseState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class CaseStateMachine {

    private static final Set<CaseState> INITIAL_TRANSITIONS =
            EnumSet.of(CaseState.OPEN, CaseState.INVESTIGATING);

    private static final Map<CaseState, Set<CaseState>> TRANSITIONS = transitions();

    public boolean canTransition(CaseState fromState, CaseState toState) {
        if (toState == null) {
            return false;
        }
        return fromState == null
                ? INITIAL_TRANSITIONS.contains(toState)
                : TRANSITIONS.get(fromState).contains(toState);
    }

    public void validateTransition(CaseState fromState, CaseState toState) {
        if (!canTransition(fromState, toState)) {
            throw new IllegalStateTransitionException(
                    "Transition from " + fromState + " to " + toState + " is not allowed");
        }
    }

    private static Map<CaseState, Set<CaseState>> transitions() {
        Map<CaseState, Set<CaseState>> transitions = new EnumMap<>(CaseState.class);
        transitions.put(CaseState.OPEN, EnumSet.of(CaseState.INVESTIGATING, CaseState.PENDING_REVIEW));
        transitions.put(CaseState.INVESTIGATING, EnumSet.of(CaseState.AUTO_RESOLVED, CaseState.PENDING_REVIEW));
        transitions.put(CaseState.PENDING_REVIEW, EnumSet.of(CaseState.APPROVED, CaseState.REJECTED));
        transitions.put(CaseState.AUTO_RESOLVED, EnumSet.of(CaseState.CLOSED));
        transitions.put(CaseState.APPROVED, EnumSet.of(CaseState.CLOSED));
        transitions.put(CaseState.REJECTED, EnumSet.of(CaseState.CLOSED, CaseState.INVESTIGATING));
        transitions.put(CaseState.CLOSED, EnumSet.noneOf(CaseState.class));
        return Map.copyOf(transitions);
    }
}
