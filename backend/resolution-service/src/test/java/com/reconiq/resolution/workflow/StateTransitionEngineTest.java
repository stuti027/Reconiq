package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateTransitionEngineTest {

    private static final String CASE_ID = "case-1";
    private static final Instant TRANSITION_TIME = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void transitionsEveryLegalEdgePersistsSnapshotAndRecordsAudit() {
        for (Edge edge : legalEdges()) {
            InMemoryCasePersistence cases = new InMemoryCasePersistence();
            InMemoryResolutionHistory history = new InMemoryResolutionHistory();
            CaseSnapshot baseSnapshot = snapshot(edge.from() == null ? edge.to() : edge.from());
            if (edge.from() != null) {
                cases.save(baseSnapshot);
            }

            StateTransitionEngine engine = engine(cases, history);
            WorkflowTransition command = validTransition(edge.to());
            CaseSnapshot updated = edge.from() == null
                    ? engine.transition(baseSnapshot, command)
                    : engine.transition(CASE_ID, command);

            assertThat(updated.state()).as("persisted edge %s -> %s", edge.from(), edge.to())
                    .isEqualTo(edge.to());
            assertThat(updated.transactionIds()).containsExactly("txn-1");
            assertThat(cases.findById(CASE_ID)).contains(updated);
            assertThat(history.findByCaseId(CASE_ID)).containsExactly(new TransitionRecord(
                    CASE_ID, edge.from(), edge.to(), command.action(), command.actor(), command.reason(),
                    TRANSITION_TIME));
        }
    }

    @Test
    void rejectsIllegalEdgesWithoutChangingCaseOrWritingAudit() {
        for (CaseState from : CaseState.values()) {
            for (CaseState to : CaseState.values()) {
                CaseStateMachine stateMachine = new CaseStateMachine();
                if (stateMachine.canTransition(from, to)) {
                    continue;
                }

                InMemoryCasePersistence cases = new InMemoryCasePersistence();
                InMemoryResolutionHistory history = new InMemoryResolutionHistory();
                CaseSnapshot initial = snapshot(from);
                cases.save(initial);
                StateTransitionEngine engine = engine(cases, history);

                assertThatThrownBy(() -> engine.transition(CASE_ID, validTransition(to)))
                        .isInstanceOf(IllegalStateTransitionException.class);
                assertThat(cases.findById(CASE_ID)).contains(initial);
                assertThat(history.findByCaseId(CASE_ID)).isEmpty();
            }
        }
    }

    @Test
    void requiresAnInitialSnapshotForFirstTransition() {
        InMemoryCasePersistence cases = new InMemoryCasePersistence();
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();

        assertThatThrownBy(() -> engine(cases, history)
                .transition(CASE_ID, validTransition(CaseState.OPEN)))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("does not exist");
        assertThat(history.findByCaseId(CASE_ID)).isEmpty();
    }

    @Test
    void rejectsInitialSnapshotWhoseStateDoesNotMatchTarget() {
        InMemoryCasePersistence cases = new InMemoryCasePersistence();
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();

        assertThatThrownBy(() -> engine(cases, history)
                .transition(snapshot(CaseState.OPEN), validTransition(CaseState.INVESTIGATING)))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("must match");
        assertThat(cases.findById(CASE_ID)).isEmpty();
        assertThat(history.findByCaseId(CASE_ID)).isEmpty();
    }

    @Test
    void validatesRequiredAutoResolutionDetailsBeforePersistence() {
        assertValidationFailure(
                CaseState.INVESTIGATING,
                new WorkflowTransition(CaseState.AUTO_RESOLVED, "", List.of("evidence-1"), null, null, null));
        assertValidationFailure(
                CaseState.INVESTIGATING,
                new WorkflowTransition(CaseState.AUTO_RESOLVED, "duplicate", List.of(" "), null, null, null));
    }

    @Test
    void validatesRequiredApprovalAndRejectionDetailsBeforePersistence() {
        for (CaseState target : List.of(CaseState.APPROVED, CaseState.REJECTED)) {
            assertValidationFailure(
                    CaseState.PENDING_REVIEW,
                    new WorkflowTransition(target, null, List.of(), " ", "valid reason", null));
            assertValidationFailure(
                    CaseState.PENDING_REVIEW,
                    new WorkflowTransition(target, null, List.of(), "reviewer", "\t", null));
        }
    }

    @Test
    void recordsAuditOnlyAfterAValidPersistenceUpdate() {
        InMemoryCasePersistence cases = new InMemoryCasePersistence();
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        cases.save(snapshot(CaseState.INVESTIGATING));

        WorkflowTransition command = new WorkflowTransition(
                CaseState.AUTO_RESOLVED,
                "duplicate transaction",
                List.of("txn-1"),
                "system",
                "matched evidence",
                ResolutionAction.FEE_WRITE_OFF);

        engine(cases, history).transition(CASE_ID, command);

        TransitionRecord record = history.findByCaseId(CASE_ID).get(0);
        assertThat(record.actor()).isEqualTo("system");
        assertThat(record.action()).isEqualTo(ResolutionAction.FEE_WRITE_OFF);
        assertThat(record.transitionedAt()).isEqualTo(TRANSITION_TIME);
    }

    private void assertValidationFailure(CaseState from, WorkflowTransition command) {
        InMemoryCasePersistence cases = new InMemoryCasePersistence();
        InMemoryResolutionHistory history = new InMemoryResolutionHistory();
        CaseSnapshot initial = snapshot(from);
        cases.save(initial);

        assertThatThrownBy(() -> engine(cases, history).transition(CASE_ID, command))
                .isInstanceOf(WorkflowValidationException.class);
        assertThat(cases.findById(CASE_ID)).contains(initial);
        assertThat(history.findByCaseId(CASE_ID)).isEmpty();
    }

    private static StateTransitionEngine engine(
            CasePersistencePort cases,
            ResolutionHistoryPort history) {
        return new StateTransitionEngine(
                cases,
                history,
                new CaseStateMachine(),
                new WorkflowValidator(),
                Clock.fixed(TRANSITION_TIME, ZoneOffset.UTC));
    }

    private static CaseSnapshot snapshot(CaseState state) {
        return new CaseSnapshot(
                CASE_ID, state, List.of("txn-1"), new BigDecimal("12.50"), new BigDecimal("0.75"));
    }

    private static WorkflowTransition validTransition(CaseState target) {
        String rootCause = target == CaseState.AUTO_RESOLVED ? "duplicate transaction" : null;
        List<String> evidence = target == CaseState.AUTO_RESOLVED ? List.of("txn-1") : List.of();
        String actor = target == CaseState.APPROVED || target == CaseState.REJECTED ? "reviewer" : null;
        String reason = actor == null ? null : "review complete";
        return new WorkflowTransition(target, rootCause, evidence, actor, reason, null);
    }

    private static List<Edge> legalEdges() {
        return List.of(
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
    }

    private record Edge(CaseState from, CaseState to) {
    }

    private static final class InMemoryCasePersistence implements CasePersistencePort {

        private final Map<String, CaseSnapshot> cases = new HashMap<>();

        @Override
        public Optional<CaseSnapshot> findById(String caseId) {
            return Optional.ofNullable(cases.get(caseId));
        }

        @Override
        public CaseSnapshot save(CaseSnapshot snapshot) {
            cases.put(snapshot.caseId(), snapshot);
            return snapshot;
        }
    }

    private static final class InMemoryResolutionHistory implements ResolutionHistoryPort {

        private final Map<String, List<TransitionRecord>> transitions = new HashMap<>();

        @Override
        public void recordTransition(TransitionRecord transition) {
            transitions.computeIfAbsent(transition.caseId(), ignored -> new ArrayList<>()).add(transition);
        }

        @Override
        public List<TransitionRecord> findByCaseId(String caseId) {
            return List.copyOf(transitions.getOrDefault(caseId, List.of()));
        }
    }
}
