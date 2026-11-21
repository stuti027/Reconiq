package com.reconiq.resolution.api;

import com.reconiq.resolution.engine.ApprovalEscalationService;
import com.reconiq.resolution.engine.CaseWorkflowHistory;
import com.reconiq.resolution.engine.ResolutionRuleEngine;
import com.reconiq.resolution.engine.ResolutionRuleResult;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.workflow.CaseNotFoundException;
import com.reconiq.resolution.workflow.IllegalStateTransitionException;
import com.reconiq.resolution.workflow.WorkflowValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResolutionWorkflowController.class)
class ResolutionWorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResolutionRuleEngine resolutionRuleEngine;

    @MockBean
    private ApprovalEscalationService approvalEscalationService;

    @Test
    void rcaCallbackReturnsOkAndSupportsIdempotentResult() throws Exception {
        ResolutionRuleResult result = new ResolutionRuleResult(
                "case-1", CaseState.PENDING_REVIEW, true, null, null,
                "Duplicate investigation result skipped");
        when(resolutionRuleEngine.process(any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/workflows/rca-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInvestigationPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.skipped").value(true));

        mockMvc.perform(post("/api/v1/workflows/rca-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInvestigationPayload()))
                .andExpect(status().isOk());
    }

    @Test
    void approveDelegatesToEscalationService() throws Exception {
        CaseSnapshot snapshot = snapshot(CaseState.CLOSED);
        when(approvalEscalationService.approveCase(
                "case-1", "reviewer-1", ResolutionAction.FEE_WRITE_OFF, "approved"))
                .thenReturn(snapshot);

        mockMvc.perform(post("/api/v1/workflows/case-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerId":"reviewer-1","action":"FEE_WRITE_OFF","notes":"approved"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"));

        verify(approvalEscalationService).approveCase(
                "case-1", "reviewer-1", ResolutionAction.FEE_WRITE_OFF, "approved");
    }

    @Test
    void rejectDelegatesToEscalationService() throws Exception {
        when(approvalEscalationService.rejectCase(
                "case-1", "reviewer-1", "needs more work", true))
                .thenReturn(snapshot(CaseState.CLOSED));

        mockMvc.perform(post("/api/v1/workflows/case-1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerId":"reviewer-1","notes":"needs more work","closeImmediately":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"));
    }

    @Test
    void reopenDelegatesToEscalationService() throws Exception {
        when(approvalEscalationService.reopenForInvestigation(
                "case-1", "reviewer-1", "review failed"))
                .thenReturn(snapshot(CaseState.INVESTIGATING));

        mockMvc.perform(post("/api/v1/workflows/case-1/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"reviewer-1","reason":"review failed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INVESTIGATING"));
    }

    @Test
    void historyReturnsTimeline() throws Exception {
        when(approvalEscalationService.getCaseWorkflowHistory("case-1"))
                .thenReturn(new CaseWorkflowHistory(snapshot(CaseState.PENDING_REVIEW), List.of()));

        mockMvc.perform(get("/api/v1/workflows/case-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.caseId").value("case-1"))
                .andExpect(jsonPath("$.auditEntries").isArray());
    }

    @Test
    void illegalStateTransitionReturnsConflict() throws Exception {
        when(approvalEscalationService.rejectCase(any(), any(), any(), eq(false)))
                .thenThrow(new IllegalStateTransitionException("Invalid transition"));

        mockMvc.perform(post("/api/v1/workflows/case-1/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerId":"reviewer-1","notes":"reject","closeImmediately":false}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void workflowValidationReturnsUnprocessableEntity() throws Exception {
        when(approvalEscalationService.reopenForInvestigation(any(), any(), any()))
                .thenThrow(new WorkflowValidationException("Invalid reopen"));

        mockMvc.perform(post("/api/v1/workflows/case-1/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"reviewer-1","reason":"invalid"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingCaseReturnsNotFound() throws Exception {
        when(approvalEscalationService.getCaseWorkflowHistory("missing"))
                .thenThrow(new CaseNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/workflows/missing/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPayloadReturnsUnprocessableEntity() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/case-1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private static CaseSnapshot snapshot(CaseState state) {
        return new CaseSnapshot(
                "case-1", state, List.of("txn-1"),
                new BigDecimal("5.00"), new BigDecimal("0.20"));
    }

    private static String validInvestigationPayload() {
        return """
                {
                  "schema_version": 1,
                  "case_id": "case-1",
                  "source_phase": "PHASE_3",
                  "transaction_ids": ["txn-1"],
                  "status": "RESOLVED",
                  "root_cause": "Duplicate transaction",
                  "cited_evidence": [{"kind":"transaction","ref_id":"txn-1"}],
                  "examined_evidence": [{"kind":"transaction","ref_id":"txn-1"}],
                  "failure_reason": null,
                  "attempts": 1,
                  "model": "gemini-test",
                  "investigated_at": "2026-11-21T20:00:00Z"
                }
                """;
    }
}
