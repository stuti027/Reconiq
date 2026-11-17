package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CaseState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseStateMachineTest {

    private final CaseStateMachine stateMachine = new CaseStateMachine();

    @Test
    void allowsEveryConfiguredTransition() {
        List<Edge> legalEdges = List.of(
                new Edge(null, CaseState.OPEN),
                new Edge(null, CaseState.INVESTIGATING),
                new Edge(CaseState.OPEN, CaseState.INVESTIGATING),
                new Edge(CaseState.OPEN, CaseState.PENDING_REVIEW),
                new Edge(CaseState.INVESTIGATING, CaseState.AUTO_RESOLVED),
                new Edge(CaseState.INVESTIGATING, CaseState.PENDING_REVIEW),
                new Edge(CaseState.PENDING_REVIEW, CaseState.APPROVED),
                new Edge(CaseState.PENDING_REVIEW, CaseState.REJECTED),
                new Edge(CaseState.AUTO_RESOLVED, CaseState.CLOSED),
                new Edge(CaseState.APPROVED, CaseState.CLOSED),
                new Edge(CaseState.REJECTED, CaseState.CLOSED),
                new Edge(CaseState.REJECTED, CaseState.INVESTIGATING));

        for (Edge edge : legalEdges) {
            assertThat(stateMachine.canTransition(edge.from(), edge.to()))
                    .as("transition from %s to %s", edge.from(), edge.to())
                    .isTrue();
            stateMachine.validateTransition(edge.from(), edge.to());
        }
    }

    @Test
    void rejectsEveryUnconfiguredTransitionAndNullDestination() {
        List<CaseState> sources = new ArrayList<>();
        sources.add(null);
        sources.addAll(List.of(CaseState.values()));
        List<CaseState> destinations = new ArrayList<>(List.of(CaseState.values()));
        destinations.add(null);

        for (CaseState from : sources) {
            for (CaseState to : destinations) {
                if (!stateMachine.canTransition(from, to)) {
                    assertThatThrownBy(() -> stateMachine.validateTransition(from, to))
                            .isInstanceOf(IllegalStateTransitionException.class);
                }
            }
        }
    }

    private record Edge(CaseState from, CaseState to) {
    }
}
