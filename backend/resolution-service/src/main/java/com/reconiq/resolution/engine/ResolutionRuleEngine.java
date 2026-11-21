package com.reconiq.resolution.engine;

import com.reconiq.resolution.engine.dto.InvestigationResultPayload;
import com.reconiq.resolution.port.CanonicalAmountPort;
import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.workflow.StateTransitionEngine;
import com.reconiq.resolution.workflow.WorkflowTransition;
import com.reconiq.resolution.workflow.WorkflowValidationException;
import com.reconiq.resolution.workflow.CaseNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class ResolutionRuleEngine {

    private static final String SYSTEM_ACTOR = "system";
    private static final Pattern FEE_OR_CHARGE = Pattern.compile("\\b(fees?|charges?)\\b", Pattern.CASE_INSENSITIVE);

    private final CasePersistencePort casePersistencePort;
    private final CanonicalAmountPort canonicalAmountPort;
    private final StateTransitionEngine stateTransitionEngine;
    private final ResolutionActionExecutor actionExecutor;
    private final BigDecimal maxAutoResolveAmount;
    private final BigDecimal maxAnomalyScore;

    public ResolutionRuleEngine(
            CasePersistencePort casePersistencePort,
            CanonicalAmountPort canonicalAmountPort,
            StateTransitionEngine stateTransitionEngine,
            ResolutionActionExecutor actionExecutor,
            @Value("${reconiq.stp.max-auto-resolve-amount}") BigDecimal maxAutoResolveAmount,
            @Value("${reconiq.stp.max-anomaly-score}") BigDecimal maxAnomalyScore) {
        this.casePersistencePort = Objects.requireNonNull(casePersistencePort);
        this.canonicalAmountPort = Objects.requireNonNull(canonicalAmountPort);
        this.stateTransitionEngine = Objects.requireNonNull(stateTransitionEngine);
        this.actionExecutor = Objects.requireNonNull(actionExecutor);
        this.maxAutoResolveAmount = Objects.requireNonNull(maxAutoResolveAmount);
        this.maxAnomalyScore = Objects.requireNonNull(maxAnomalyScore);
    }

    public ResolutionRuleResult process(InvestigationResultPayload result) {
        Objects.requireNonNull(result, "result must not be null");

        CaseSnapshot snapshot = casePersistencePort.findById(result.caseId())
                .orElseThrow(() -> new CaseNotFoundException(result.caseId()));
        if (hasAlreadyPassedInvestigation(snapshot.state())) {
            return new ResolutionRuleResult(
                    result.caseId(),
                    snapshot.state(),
                    true,
                    null,
                    null,
                    "Duplicate investigation result skipped because the case is already " + snapshot.state());
        }

        if (snapshot.state() == CaseState.OPEN) {
            snapshot = stateTransitionEngine.transition(result.caseId(), new WorkflowTransition(
                    CaseState.INVESTIGATING,
                    null,
                    List.of(),
                    SYSTEM_ACTOR,
                    "Investigation result received",
                    null));
        }

        BigDecimal discrepancyAmount = canonicalAmountPort.findDiscrepancyAmount(result.transactionIds())
                .orElseThrow(() -> new WorkflowValidationException(
                        "No canonical discrepancy amount found for case " + result.caseId()));

        boolean withinAmountLimit = discrepancyAmount.compareTo(maxAutoResolveAmount) <= 0;
        boolean withinAnomalyLimit = snapshot.anomalyScore().compareTo(maxAnomalyScore) <= 0;
        boolean canAutoResolve = result.status() == InvestigationResultPayload.Status.RESOLVED
                && !result.citedEvidence().isEmpty()
                && withinAmountLimit
                && withinAnomalyLimit;

        if (canAutoResolve) {
            return autoResolve(result, discrepancyAmount);
        }
        return sendToReview(result, discrepancyAmount, withinAmountLimit, withinAnomalyLimit);
    }

    private ResolutionRuleResult autoResolve(InvestigationResultPayload result, BigDecimal discrepancyAmount) {
        ResolutionAction action = FEE_OR_CHARGE.matcher(result.rootCause()).find()
                ? ResolutionAction.FEE_WRITE_OFF
                : ResolutionAction.LEDGER_ADJUSTMENT;
        List<String> citedEvidence = result.citedEvidence().stream()
                .map(reference -> reference.kind().value() + ":" + reference.refId())
                .toList();

        stateTransitionEngine.transition(result.caseId(), new WorkflowTransition(
                CaseState.AUTO_RESOLVED,
                result.rootCause(),
                citedEvidence,
                SYSTEM_ACTOR,
                "Resolved within STP amount and anomaly limits",
                action));
        CaseSnapshot closed = stateTransitionEngine.transition(result.caseId(), new WorkflowTransition(
                CaseState.CLOSED,
                null,
                List.of(),
                SYSTEM_ACTOR,
                "Automatic resolution completed",
                null));
        ActionExecutionSummary execution = actionExecutor.execute(result.caseId(), action, discrepancyAmount);
        return new ResolutionRuleResult(
                result.caseId(),
                closed.state(),
                false,
                discrepancyAmount,
                execution,
                "Automatically resolved within STP limits");
    }

    private ResolutionRuleResult sendToReview(
            InvestigationResultPayload result,
            BigDecimal discrepancyAmount,
            boolean withinAmountLimit,
            boolean withinAnomalyLimit) {
        String reason = reviewReason(result.status(), withinAmountLimit, withinAnomalyLimit);
        CaseSnapshot pendingReview = stateTransitionEngine.transition(result.caseId(), new WorkflowTransition(
                CaseState.PENDING_REVIEW,
                null,
                List.of(),
                SYSTEM_ACTOR,
                reason,
                ResolutionAction.MANUAL_ESCALATION));
        ActionExecutionSummary execution = actionExecutor.execute(
                result.caseId(), ResolutionAction.MANUAL_ESCALATION, discrepancyAmount);
        return new ResolutionRuleResult(
                result.caseId(),
                pendingReview.state(),
                false,
                discrepancyAmount,
                execution,
                reason);
    }

    private static String reviewReason(
            InvestigationResultPayload.Status status,
            boolean withinAmountLimit,
            boolean withinAnomalyLimit) {
        if (status != InvestigationResultPayload.Status.RESOLVED) {
            return "Investigation status " + status + " requires manual review";
        }
        if (!withinAmountLimit) {
            return "Discrepancy exceeds the automatic-resolution amount limit";
        }
        if (!withinAnomalyLimit) {
            return "Anomaly score exceeds the automatic-resolution limit";
        }
        return "Investigation result does not meet automatic-resolution evidence requirements";
    }

    private static boolean hasAlreadyPassedInvestigation(CaseState state) {
        return state != CaseState.OPEN && state != CaseState.INVESTIGATING;
    }
}
