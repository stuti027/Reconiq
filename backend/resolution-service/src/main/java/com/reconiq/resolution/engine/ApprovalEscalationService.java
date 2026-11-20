package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import com.reconiq.resolution.workflow.StateTransitionEngine;
import com.reconiq.resolution.workflow.WorkflowTransition;
import com.reconiq.resolution.workflow.WorkflowValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ApprovalEscalationService {

    private final CasePersistencePort casePersistencePort;
    private final ResolutionHistoryPort resolutionHistoryPort;
    private final StateTransitionEngine stateTransitionEngine;
    private final ResolutionActionExecutor resolutionActionExecutor;

    public ApprovalEscalationService(
            CasePersistencePort casePersistencePort,
            ResolutionHistoryPort resolutionHistoryPort,
            StateTransitionEngine stateTransitionEngine,
            ResolutionActionExecutor resolutionActionExecutor) {
        this.casePersistencePort = Objects.requireNonNull(casePersistencePort);
        this.resolutionHistoryPort = Objects.requireNonNull(resolutionHistoryPort);
        this.stateTransitionEngine = Objects.requireNonNull(stateTransitionEngine);
        this.resolutionActionExecutor = Objects.requireNonNull(resolutionActionExecutor);
    }

    @Transactional
    public CaseSnapshot approveCase(
            String caseId,
            String reviewerId,
            ResolutionAction overrideAction,
            String notes) {
        if (overrideAction == null) {
            throw new WorkflowValidationException("Resolution action is required for approval");
        }

        CaseSnapshot current = requireCase(caseId);
        stateTransitionEngine.transition(caseId, new WorkflowTransition(
                CaseState.APPROVED,
                null,
                List.of(),
                reviewerId,
                notes,
                overrideAction));
        resolutionActionExecutor.execute(caseId, overrideAction, current.discrepancyAmount());
        return stateTransitionEngine.transition(caseId, new WorkflowTransition(
                CaseState.CLOSED,
                null,
                List.of(),
                reviewerId,
                notes,
                null));
    }

    @Transactional
    public CaseSnapshot rejectCase(
            String caseId,
            String reviewerId,
            String notes,
            boolean closeImmediately) {
        CaseSnapshot rejected = stateTransitionEngine.transition(caseId, new WorkflowTransition(
                CaseState.REJECTED,
                null,
                List.of(),
                reviewerId,
                notes,
                null));
        if (!closeImmediately) {
            return rejected;
        }
        return stateTransitionEngine.transition(caseId, new WorkflowTransition(
                CaseState.CLOSED,
                null,
                List.of(),
                reviewerId,
                notes,
                null));
    }

    @Transactional
    public CaseSnapshot reopenForInvestigation(String caseId, String actor, String reason) {
        return stateTransitionEngine.transition(caseId, new WorkflowTransition(
                CaseState.INVESTIGATING,
                null,
                List.of(),
                actor,
                reason,
                null));
    }

    @Transactional(readOnly = true)
    public CaseWorkflowHistory getCaseWorkflowHistory(String caseId) {
        CaseSnapshot snapshot = requireCase(caseId);
        List<TransitionRecord> auditEntries = resolutionHistoryPort.findByCaseId(caseId).stream()
                .sorted(Comparator.comparing(TransitionRecord::transitionedAt))
                .toList();
        return new CaseWorkflowHistory(snapshot, auditEntries);
    }

    private CaseSnapshot requireCase(String caseId) {
        return casePersistencePort.findById(caseId)
                .orElseThrow(() -> new WorkflowValidationException("Case " + caseId + " does not exist"));
    }
}
