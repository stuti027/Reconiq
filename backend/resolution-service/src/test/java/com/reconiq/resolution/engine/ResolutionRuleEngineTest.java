package com.reconiq.resolution.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.reconiq.resolution.engine.dto.EvidenceReference;
import com.reconiq.resolution.engine.dto.InvestigationResultPayload;
import com.reconiq.resolution.port.CanonicalAmountPort;
import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.CaseState;
import com.reconiq.resolution.port.ResolutionAction;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;
import com.reconiq.resolution.workflow.StateTransitionEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutionRuleEngineTest {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10.00");
    private static final BigDecimal MAX_ANOMALY = new BigDecimal("0.75");

    @Test
    void autoResolvesLowVarianceResultsAndSelectsActionFromRootCause() {
        TestContext context = new TestContext(CaseState.INVESTIGATING, "4.00", "0.30");

        ResolutionRuleResult result = context.engine.process(resolvedResult("case-1", "Processing fee charged twice"));

        assertThat(result.finalState()).isEqualTo(CaseState.CLOSED);
        assertThat(result.skipped()).isFalse();
        assertThat(result.actionExecution().action()).isEqualTo(ResolutionAction.FEE_WRITE_OFF);
        assertThat(context.cases.findById("case-1")).get().extracting(CaseSnapshot::state)
                .isEqualTo(CaseState.CLOSED);
        assertThat(context.history.transitionsFor("case-1"))
                .extracting(TransitionRecord::toState)
                .containsExactly(CaseState.AUTO_RESOLVED, CaseState.CLOSED);
    }

    @Test
    void autoResolutionWithoutFeeOrChargeSelectsLedgerAdjustment() {
        TestContext context = new TestContext(CaseState.INVESTIGATING, "4.00", "0.30");

        ResolutionRuleResult result = context.engine.process(resolvedResult("case-1", "Duplicate transaction"));

        assertThat(result.finalState()).isEqualTo(CaseState.CLOSED);
        assertThat(result.actionExecution().action()).isEqualTo(ResolutionAction.LEDGER_ADJUSTMENT);
        assertThat(context.history.transitionsFor("case-1").get(0).action())
                .isEqualTo(ResolutionAction.LEDGER_ADJUSTMENT);
    }

    @Test
    void escalatesHighVarianceResultsToPendingReview() {
        TestContext context = new TestContext(CaseState.INVESTIGATING, "10.01", "0.30");

        ResolutionRuleResult result = context.engine.process(resolvedResult("case-1", "Duplicate transaction"));

        assertThat(result.finalState()).isEqualTo(CaseState.PENDING_REVIEW);
        assertThat(result.actionExecution().action()).isEqualTo(ResolutionAction.MANUAL_ESCALATION);
        assertThat(context.history.transitionsFor("case-1").get(0).toState())
                .isEqualTo(CaseState.PENDING_REVIEW);
    }

    @Test
    void escalatesResultsAboveTheAnomalyScoreLimit() {
        TestContext context = new TestContext(CaseState.INVESTIGATING, "1.00", "0.76");

        ResolutionRuleResult result = context.engine.process(resolvedResult("case-1", "Duplicate transaction"));

        assertThat(result.finalState()).isEqualTo(CaseState.PENDING_REVIEW);
        assertThat(result.actionExecution().action()).isEqualTo(ResolutionAction.MANUAL_ESCALATION);
    }

    @Test
    void routesLowConfidenceAndFailedResultsToPendingReview() {
        for (InvestigationResultPayload result : List.of(
                result("case-low-confidence", InvestigationResultPayload.Status.LOW_CONFIDENCE, null, null, null, 1),
                result("case-failed", InvestigationResultPayload.Status.FAILED, null, null, "UPSTREAM_ERROR", 0))) {
            TestContext context = new TestContext(result.caseId(), CaseState.INVESTIGATING, "1.00", "0.20");

            ResolutionRuleResult processingResult = context.engine.process(result);

            assertThat(processingResult.finalState()).isEqualTo(CaseState.PENDING_REVIEW);
            assertThat(processingResult.actionExecution().action()).isEqualTo(ResolutionAction.MANUAL_ESCALATION);
        }
    }

    @Test
    void skipsDuplicateResultsForCasesPastInvestigationWithoutQueryingAmounts() {
        TestContext context = new TestContext(CaseState.PENDING_REVIEW, "1.00", "0.20");

        ResolutionRuleResult result = context.engine.process(resolvedResult("case-1", "Duplicate transaction"));

        assertThat(result.skipped()).isTrue();
        assertThat(result.finalState()).isEqualTo(CaseState.PENDING_REVIEW);
        assertThat(result.actionExecution()).isNull();
        assertThat(context.canonicalAmountQueries.get()).isZero();
        assertThat(context.history.transitionsFor("case-1")).isEmpty();
    }

    @Test
    void serializesInvestigationResultsUsingThePhaseFourSchemaNamesAndValues() throws Exception {
        InvestigationResultPayload payload = resolvedResult("case-1", "Duplicate transaction");
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(payload));

        assertThat(json.size()).isEqualTo(12);
        assertThat(json.has("schema_version")).isTrue();
        assertThat(json.has("case_id")).isTrue();
        assertThat(json.has("source_phase")).isTrue();
        assertThat(json.has("transaction_ids")).isTrue();
        assertThat(json.has("status")).isTrue();
        assertThat(json.has("root_cause")).isTrue();
        assertThat(json.has("cited_evidence")).isTrue();
        assertThat(json.has("examined_evidence")).isTrue();
        assertThat(json.has("failure_reason")).isTrue();
        assertThat(json.has("attempts")).isTrue();
        assertThat(json.has("model")).isTrue();
        assertThat(json.has("investigated_at")).isTrue();
        assertThat(json.get("source_phase").asText()).isEqualTo("PHASE_3");
        assertThat(json.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(json.get("cited_evidence").get(0).get("kind").asText()).isEqualTo("transaction");
        assertThat(json.get("cited_evidence").get(0).has("ref_id")).isTrue();
        assertThat(json.get("investigated_at").asText()).endsWith("Z");
    }

    private static InvestigationResultPayload resolvedResult(String caseId, String rootCause) {
        EvidenceReference evidence = new EvidenceReference(EvidenceReference.Kind.TRANSACTION, "event-1");
        return result(
                caseId,
                InvestigationResultPayload.Status.RESOLVED,
                rootCause,
                List.of(evidence),
                null,
                1);
    }

    private static InvestigationResultPayload result(
            String caseId,
            InvestigationResultPayload.Status status,
            String rootCause,
            List<EvidenceReference> citedEvidence,
            String failureReason,
            int attempts) {
        List<EvidenceReference> cited = citedEvidence == null ? List.of() : citedEvidence;
        return new InvestigationResultPayload(
                1,
                caseId,
                InvestigationResultPayload.SourcePhase.PHASE_3,
                List.of("txn-1"),
                status,
                rootCause,
                cited,
                cited,
                failureReason,
                attempts,
                "test-model",
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static final class TestContext {

        private final InMemoryCases cases;
        private final InMemoryHistory history = new InMemoryHistory();
        private final AtomicInteger canonicalAmountQueries = new AtomicInteger();
        private final ResolutionRuleEngine engine;

        private TestContext(CaseState state, String discrepancyAmount, String anomalyScore) {
            this("case-1", state, discrepancyAmount, anomalyScore);
        }

        private TestContext(String caseId, CaseState state, String discrepancyAmount, String anomalyScore) {
            cases = new InMemoryCases();
            cases.save(new CaseSnapshot(
                    caseId,
                    state,
                    List.of("txn-1"),
                    new BigDecimal(discrepancyAmount),
                    new BigDecimal(anomalyScore)));
            CanonicalAmountPort amounts = transactionIds -> {
                canonicalAmountQueries.incrementAndGet();
                return Optional.of(new BigDecimal(discrepancyAmount));
            };
            engine = new ResolutionRuleEngine(
                    cases,
                    amounts,
                    new StateTransitionEngine(cases, history),
                    new ResolutionActionExecutor(),
                    MAX_AMOUNT,
                    MAX_ANOMALY);
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
            return List.copyOf(transitionsFor(caseId));
        }

        private List<TransitionRecord> transitionsFor(String caseId) {
            return transitions.getOrDefault(caseId, List.of());
        }
    }
}
