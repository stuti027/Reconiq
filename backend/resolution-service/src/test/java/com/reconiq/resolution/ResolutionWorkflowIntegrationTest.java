package com.reconiq.resolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.resolution.port.CanonicalAmountPort;
import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ResolutionWorkflowIntegrationTest.TestPortsConfiguration.class)
class ResolutionWorkflowIntegrationTest {

    private static final String TRANSACTION_ID = "txn-phase5-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestWorkflowPorts workflowPorts;

    @BeforeEach
    void resetPorts() {
        workflowPorts.clear();
    }

    @Test
    void resolvedLowAmountCallbackClosesCaseAndDuplicateCallbackIsIdempotent() throws Exception {
        seedCase("auto-resolve-case", "5.00", "0.20");
        String callback = callback(
                "auto-resolve-case",
                "RESOLVED",
                "\"Duplicate transaction\"",
                "[{\"kind\":\"transaction\",\"ref_id\":\"" + TRANSACTION_ID + "\"}]",
                "null",
                1);

        JsonNode firstResult = postCallback(callback);
        assertThat(firstResult.path("finalState").asText()).isEqualTo("CLOSED");
        assertThat(firstResult.path("skipped").asBoolean()).isFalse();
        JsonNode initialHistory = getHistory("auto-resolve-case");
        assertThat(initialHistory.path("snapshot").path("state").asText()).isEqualTo("CLOSED");
        assertThat(states(initialHistory)).containsExactly("INVESTIGATING", "AUTO_RESOLVED", "CLOSED");

        JsonNode duplicateResult = postCallback(callback);
        assertThat(duplicateResult.path("finalState").asText()).isEqualTo("CLOSED");
        assertThat(duplicateResult.path("skipped").asBoolean()).isTrue();
        JsonNode historyAfterDuplicate = getHistory("auto-resolve-case");
        assertThat(states(historyAfterDuplicate)).containsExactly("INVESTIGATING", "AUTO_RESOLVED", "CLOSED");
        assertThat(historyAfterDuplicate.path("auditEntries").size())
                .isEqualTo(initialHistory.path("auditEntries").size());
    }

    @Test
    void lowConfidenceCallbackCanBeApprovedAndHistoryContainsEveryTransition() throws Exception {
        seedCase("approval-case", "8.00", "0.30");
        JsonNode callbackResult = postCallback(callback(
                "approval-case", "LOW_CONFIDENCE", "\"Possible duplicate\"", "[]", "null", 1));
        assertThat(callbackResult.path("finalState").asText()).isEqualTo("PENDING_REVIEW");

        mockMvc.perform(post("/api/v1/workflows/approval-case/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerId":"supervisor-1","action":"LEDGER_ADJUSTMENT","notes":"verified"}
                                """))
                .andExpect(status().isOk());

        JsonNode history = getHistory("approval-case");
        assertThat(history.path("snapshot").path("state").asText()).isEqualTo("CLOSED");
        assertThat(states(history))
                .containsExactly("INVESTIGATING", "PENDING_REVIEW", "APPROVED", "CLOSED");
        JsonNode entries = history.path("auditEntries");
        assertThat(entries.get(0).path("fromState").asText()).isEqualTo("OPEN");
        assertThat(entries.get(1).path("fromState").asText()).isEqualTo("INVESTIGATING");
        assertThat(entries.get(2).path("fromState").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(entries.get(2).path("actor").asText()).isEqualTo("supervisor-1");
        assertThat(entries.get(2).path("action").asText()).isEqualTo("LEDGER_ADJUSTMENT");
        assertThat(entries.get(2).path("reason").asText()).isEqualTo("verified");
        assertThat(entries.get(3).path("fromState").asText()).isEqualTo("APPROVED");
        assertThat(entries.get(3).path("actor").asText()).isEqualTo("supervisor-1");
    }

    @Test
    void failedCallbackCanBeRejectedAndReopenedForInvestigation() throws Exception {
        seedCase("reopen-case", "7.00", "0.40");
        JsonNode callbackResult = postCallback(callback(
                "reopen-case", "FAILED", "null", "[]", "\"MODEL_UNAVAILABLE\"", 0));
        assertThat(callbackResult.path("finalState").asText()).isEqualTo("PENDING_REVIEW");

        mockMvc.perform(post("/api/v1/workflows/reopen-case/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reviewerId":"supervisor-2","notes":"investigation required","closeImmediately":false}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workflows/reopen-case/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actor":"supervisor-2","reason":"collect additional evidence"}
                                """))
                .andExpect(status().isOk());

        JsonNode history = getHistory("reopen-case");
        assertThat(history.path("snapshot").path("state").asText()).isEqualTo("INVESTIGATING");
        assertThat(states(history))
                .containsExactly("INVESTIGATING", "PENDING_REVIEW", "REJECTED", "INVESTIGATING");
        JsonNode entries = history.path("auditEntries");
        assertThat(entries.get(2).path("fromState").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(entries.get(2).path("actor").asText()).isEqualTo("supervisor-2");
        assertThat(entries.get(3).path("fromState").asText()).isEqualTo("REJECTED");
        assertThat(entries.get(3).path("reason").asText()).isEqualTo("collect additional evidence");
    }

    private void seedCase(String caseId, String amount, String anomalyScore) {
        List<String> transactionIds = List.of(TRANSACTION_ID);
        workflowPorts.save(new CaseSnapshot(
                caseId,
                CaseState.OPEN,
                transactionIds,
                new BigDecimal(amount),
                new BigDecimal(anomalyScore)));
        workflowPorts.setDiscrepancyAmount(transactionIds, new BigDecimal(amount));
    }

    private JsonNode postCallback(String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/workflows/rca-callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getHistory(String caseId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/workflows/{caseId}/history", caseId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> states(JsonNode history) {
        var entries = history.path("auditEntries");
        List<String> result = new java.util.ArrayList<>();
        entries.forEach(entry -> result.add(entry.path("toState").asText()));
        return result;
    }

    private static String callback(
            String caseId,
            String status,
            String rootCause,
            String citedEvidence,
            String failureReason,
            int attempts) {
        return """
                {
                  "schema_version": 1,
                  "case_id": "%s",
                  "source_phase": "PHASE_3",
                  "transaction_ids": ["%s"],
                  "status": "%s",
                  "root_cause": %s,
                  "cited_evidence": %s,
                  "examined_evidence": %s,
                  "failure_reason": %s,
                  "attempts": %d,
                  "model": "integration-test",
                  "investigated_at": "2026-11-21T20:00:00Z"
                }
                """.formatted(caseId, TRANSACTION_ID, status, rootCause, citedEvidence, citedEvidence,
                failureReason, attempts);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestPortsConfiguration {

        @Bean
        TestWorkflowPorts testWorkflowPorts() {
            return new TestWorkflowPorts();
        }

        @Bean
        @Primary
        CasePersistencePort workflowCasePersistencePort(TestWorkflowPorts ports) {
            return ports.casePersistencePort();
        }

        @Bean
        @Primary
        ResolutionHistoryPort workflowResolutionHistoryPort(TestWorkflowPorts ports) {
            return ports.resolutionHistoryPort();
        }

        @Bean
        @Primary
        CanonicalAmountPort workflowCanonicalAmountPort(TestWorkflowPorts ports) {
            return ports.canonicalAmountPort();
        }
    }

    static class TestWorkflowPorts {

        private final ConcurrentMap<String, CaseSnapshot> cases = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, CopyOnWriteArrayList<TransitionRecord>> transitions =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<List<String>, BigDecimal> discrepancyAmounts = new ConcurrentHashMap<>();

        private final CasePersistencePort casePersistencePort = new CasePersistencePort() {
            @Override
            public Optional<CaseSnapshot> findById(String caseId) {
                return Optional.ofNullable(cases.get(caseId));
            }

            @Override
            public CaseSnapshot save(CaseSnapshot snapshot) {
                cases.put(snapshot.caseId(), snapshot);
                return snapshot;
            }
        };
        private final ResolutionHistoryPort resolutionHistoryPort = new ResolutionHistoryPort() {
            @Override
            public void recordTransition(TransitionRecord transition) {
                transitions.computeIfAbsent(transition.caseId(), ignored -> new CopyOnWriteArrayList<>())
                        .add(transition);
            }

            @Override
            public List<TransitionRecord> findByCaseId(String caseId) {
                return List.copyOf(transitions.getOrDefault(caseId, new CopyOnWriteArrayList<>()));
            }
        };
        private final CanonicalAmountPort canonicalAmountPort =
                transactionIds -> Optional.ofNullable(discrepancyAmounts.get(List.copyOf(transactionIds)));

        CasePersistencePort casePersistencePort() {
            return casePersistencePort;
        }

        ResolutionHistoryPort resolutionHistoryPort() {
            return resolutionHistoryPort;
        }

        CanonicalAmountPort canonicalAmountPort() {
            return canonicalAmountPort;
        }

        CaseSnapshot save(CaseSnapshot snapshot) {
            return casePersistencePort.save(snapshot);
        }

        void setDiscrepancyAmount(List<String> transactionIds, BigDecimal amount) {
            discrepancyAmounts.put(List.copyOf(transactionIds), amount);
        }

        void clear() {
            cases.clear();
            transitions.clear();
            discrepancyAmounts.clear();
        }
    }
}
