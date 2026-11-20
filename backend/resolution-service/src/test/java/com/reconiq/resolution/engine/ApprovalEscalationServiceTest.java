package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import com.reconiq.resolution.workflow.IllegalStateTransitionException;
import com.reconiq.resolution.workflow.StateTransitionEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ApprovalEscalationServiceTest {

    private static final String CASE_ID = "case-1";

    @Test
    void approvalExecutesOverrideActionAndClosesCase() {
        TestContext context = new TestContext(CaseState.PENDING_REVIEW);

        CaseSnapshot result = context.service.approveCase(
                CASE_ID, "reviewer-1", ResolutionAction.FEE_WRITE_OFF, "Approved after review");

        assertThat(result.state()).isEqualTo(CaseState.CLOSED);
        assertThat(context.cases.findById(CASE_ID)).contains(result);
        assertThat(context.history.findByCaseId(CASE_ID))
                .extracting(TransitionRecord::toState)
                .containsExactly(CaseState.APPROVED, CaseState.CLOSED);
        assertThat(context.history.findByCaseId(CASE_ID).get(0).action())
                .isEqualTo(ResolutionAction.FEE_WRITE_OFF);
        verify(context.actionExecutor).execute(
                CASE_ID, ResolutionAction.FEE_WRITE_OFF, new BigDecimal("12.50"));
    }

    @Test
    void rejectionCanRemainOpenForInvestigationOrCloseImmediately() {
        TestContext openContext = new TestContext(CaseState.PENDING_REVIEW);

        CaseSnapshot rejected = openContext.service.rejectCase(CASE_ID, "reviewer-1", "Insufficient evidence", false);

        assertThat(rejected.state()).isEqualTo(CaseState.REJECTED);
        assertThat(openContext.history.findByCaseId(CASE_ID))
                .extracting(TransitionRecord::toState)
                .containsExactly(CaseState.REJECTED);

        TestContext closedContext = new TestContext(CaseState.PENDING_REVIEW);

        CaseSnapshot closed = closedContext.service.rejectCase(CASE_ID, "reviewer-1", "Duplicate claim", true);

        assertThat(closed.state()).isEqualTo(CaseState.CLOSED);
        assertThat(closedContext.history.findByCaseId(CASE_ID))
                .extracting(TransitionRecord::toState)
                .containsExactly(CaseState.REJECTED, CaseState.CLOSED);
    }

    @Test
    void rejectedCaseCanBeReopenedForInvestigation() {
        TestContext context = new TestContext(CaseState.REJECTED);

        CaseSnapshot reopened = context.service.reopenForInvestigation(
                CASE_ID, "investigator-1", "New evidence received");

        assertThat(reopened.state()).isEqualTo(CaseState.INVESTIGATING);
        TransitionRecord record = context.history.findByCaseId(CASE_ID).get(0);
        assertThat(record.actor()).isEqualTo("investigator-1");
        assertThat(record.reason()).isEqualTo("New evidence received");
    }

    @Test
    void approvalOfClosedCaseFailsWithoutExecutingActionOrChangingHistory() {
        TestContext context = new TestContext(CaseState.CLOSED);
        CaseSnapshot closed = context.cases.findById(CASE_ID).orElseThrow();

        assertThatThrownBy(() -> context.service.approveCase(
                CASE_ID, "reviewer-1", ResolutionAction.FEE_WRITE_OFF, "Approve"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(context.cases.findById(CASE_ID)).contains(closed);
        assertThat(context.history.findByCaseId(CASE_ID)).isEmpty();
        verifyNoInteractions(context.actionExecutor);
    }

    @Test
    void workflowHistoryCombinesSnapshotWithChronologicalAuditEntries() {
        TestContext context = new TestContext(CaseState.CLOSED);
        TransitionRecord later = transition(CaseState.APPROVED, Instant.parse("2025-01-03T00:00:00Z"));
        TransitionRecord earlier = transition(CaseState.PENDING_REVIEW, Instant.parse("2025-01-02T00:00:00Z"));
        context.history.recordTransition(later);
        context.history.recordTransition(earlier);

        CaseWorkflowHistory history = context.service.getCaseWorkflowHistory(CASE_ID);

        assertThat(history.snapshot().state()).isEqualTo(CaseState.CLOSED);
        assertThat(history.auditEntries()).containsExactly(earlier, later);
    }

    private static TransitionRecord transition(CaseState toState, Instant at) {
        return new TransitionRecord(CASE_ID, null, toState, null, null, at);
    }

    private static CaseSnapshot snapshot(CaseState state) {
        return new CaseSnapshot(
                CASE_ID, state, List.of("txn-1"), new BigDecimal("12.50"), new BigDecimal("0.75"));
    }

    private static final class TestContext {

        private final InMemoryCases cases = new InMemoryCases();
        private final InMemoryHistory history = new InMemoryHistory();
        private final ResolutionActionExecutor actionExecutor = mock(ResolutionActionExecutor.class);
        private final ApprovalEscalationService service;

        private TestContext(CaseState initialState) {
            cases.save(snapshot(initialState));
            StateTransitionEngine transitionEngine = new StateTransitionEngine(cases, history);
            service = new ApprovalEscalationService(cases, history, transitionEngine, actionExecutor);
        }
    }

    private static final class InMemoryCases implements CasePersistencePort {

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

    private static final class InMemoryHistory implements ResolutionHistoryPort {

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
