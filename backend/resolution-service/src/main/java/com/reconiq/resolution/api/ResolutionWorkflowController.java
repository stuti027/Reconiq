package com.reconiq.resolution.api;

import com.reconiq.resolution.api.dto.ReopenPayload;
import com.reconiq.resolution.api.dto.ReviewerDecisionPayload;
import com.reconiq.resolution.api.dto.ReviewerRejectionPayload;
import com.reconiq.resolution.engine.ApprovalEscalationService;
import com.reconiq.resolution.engine.CaseWorkflowHistory;
import com.reconiq.resolution.engine.ResolutionRuleEngine;
import com.reconiq.resolution.engine.ResolutionRuleResult;
import com.reconiq.resolution.engine.dto.InvestigationResultPayload;
import com.reconiq.resolution.port.CaseSnapshot;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/workflows")
public class ResolutionWorkflowController {

    private final ResolutionRuleEngine resolutionRuleEngine;
    private final ApprovalEscalationService approvalEscalationService;

    public ResolutionWorkflowController(
            ResolutionRuleEngine resolutionRuleEngine,
            ApprovalEscalationService approvalEscalationService) {
        this.resolutionRuleEngine = Objects.requireNonNull(resolutionRuleEngine);
            this.approvalEscalationService = Objects.requireNonNull(approvalEscalationService);
    }

    @PostMapping("/rca-callback")
    public ResponseEntity<ResolutionRuleResult> rcaCallback(
            @Valid @RequestBody InvestigationResultPayload payload) {
        return ResponseEntity.ok(resolutionRuleEngine.process(payload));
    }

    @PostMapping("/{caseId}/approve")
    public ResponseEntity<CaseSnapshot> approve(
            @PathVariable String caseId,
            @Valid @RequestBody ReviewerDecisionPayload payload) {
        CaseSnapshot snapshot = approvalEscalationService.approveCase(
                caseId, payload.reviewerId(), payload.action(), payload.notes());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/{caseId}/reject")
    public ResponseEntity<CaseSnapshot> reject(
            @PathVariable String caseId,
            @Valid @RequestBody ReviewerRejectionPayload payload) {
        CaseSnapshot snapshot = approvalEscalationService.rejectCase(
                caseId, payload.reviewerId(), payload.notes(), payload.closeImmediately());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/{caseId}/reopen")
    public ResponseEntity<CaseSnapshot> reopen(
            @PathVariable String caseId,
            @Valid @RequestBody ReopenPayload payload) {
        CaseSnapshot snapshot = approvalEscalationService.reopenForInvestigation(
                caseId, payload.actor(), payload.reason());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/{caseId}/history")
    public ResponseEntity<CaseWorkflowHistory> history(@PathVariable String caseId) {
        return ResponseEntity.ok(approvalEscalationService.getCaseWorkflowHistory(caseId));
    }
}
