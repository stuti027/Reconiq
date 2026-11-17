package com.reconiq.resolution.workflow;

import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
public class StateTransitionEngine {

    private final CasePersistencePort casePersistencePort;
    private final ResolutionHistoryPort resolutionHistoryPort;
    private final CaseStateMachine caseStateMachine;
    private final WorkflowValidator workflowValidator;
    private final Clock clock;

    @Autowired
    public StateTransitionEngine(
            CasePersistencePort casePersistencePort,
            ResolutionHistoryPort resolutionHistoryPort) {
        this(casePersistencePort, resolutionHistoryPort, new CaseStateMachine(), new WorkflowValidator(),
                Clock.systemUTC());
    }

    StateTransitionEngine(
            CasePersistencePort casePersistencePort,
            ResolutionHistoryPort resolutionHistoryPort,
            CaseStateMachine caseStateMachine,
            WorkflowValidator workflowValidator,
            Clock clock) {
        this.casePersistencePort = Objects.requireNonNull(casePersistencePort);
        this.resolutionHistoryPort = Objects.requireNonNull(resolutionHistoryPort);
        this.caseStateMachine = Objects.requireNonNull(caseStateMachine);
        this.workflowValidator = Objects.requireNonNull(workflowValidator);
        this.clock = Objects.requireNonNull(clock);
    }

    public CaseSnapshot transition(String caseId, WorkflowTransition transition) {
        return transition(caseId, transition, null);
    }

    public CaseSnapshot transition(CaseSnapshot initialSnapshot, WorkflowTransition transition) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot must not be null");
        return transition(initialSnapshot.caseId(), transition, initialSnapshot);
    }

    private CaseSnapshot transition(
            String caseId,
            WorkflowTransition transition,
            CaseSnapshot initialSnapshot) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(transition, "transition must not be null");

        var persistedSnapshot = casePersistencePort.findById(caseId);
        CaseSnapshot currentSnapshot = persistedSnapshot.orElse(initialSnapshot);
        if (currentSnapshot == null) {
            throw new WorkflowValidationException("Case " + caseId + " does not exist");
        }
        if (!caseId.equals(currentSnapshot.caseId())) {
            throw new WorkflowValidationException("Initial snapshot caseId does not match " + caseId);
        }
        if (persistedSnapshot.isEmpty()
                && currentSnapshot.state() != transition.targetState()) {
            throw new WorkflowValidationException("Initial snapshot state must match the target state");
        }

        CaseState fromState = persistedSnapshot.map(CaseSnapshot::state).orElse(null);
        caseStateMachine.validateTransition(fromState, transition.targetState());
        workflowValidator.validate(transition);

        CaseSnapshot updatedSnapshot = new CaseSnapshot(
                currentSnapshot.caseId(),
                transition.targetState(),
                currentSnapshot.transactionIds(),
                currentSnapshot.discrepancyAmount(),
                currentSnapshot.anomalyScore());
        CaseSnapshot savedSnapshot = casePersistencePort.save(updatedSnapshot);
        resolutionHistoryPort.recordTransition(new TransitionRecord(
                caseId,
                fromState,
                transition.targetState(),
                transition.action(),
                transition.actor(),
                transition.reason(),
                clock.instant()));
        return savedSnapshot;
    }
}
