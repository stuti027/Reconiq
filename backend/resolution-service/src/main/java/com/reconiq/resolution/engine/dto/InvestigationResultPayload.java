package com.reconiq.resolution.engine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record InvestigationResultPayload(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("case_id") String caseId,
        @JsonProperty("source_phase") SourcePhase sourcePhase,
        @JsonProperty("transaction_ids") List<String> transactionIds,
        @JsonProperty("status") Status status,
        @JsonProperty("root_cause") String rootCause,
        @JsonProperty("cited_evidence") List<EvidenceReference> citedEvidence,
        @JsonProperty("examined_evidence") List<EvidenceReference> examinedEvidence,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("attempts") int attempts,
        @JsonProperty("model") String model,
        @JsonProperty("investigated_at") Instant investigatedAt) {

    private static final int MAX_REASONING_CALLS = 2;
    private static final Pattern FAILURE_REASON_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public InvestigationResultPayload {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("schema_version must be 1");
        }
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("case_id must not be blank");
        }
        Objects.requireNonNull(sourcePhase, "source_phase must not be null");
        transactionIds = List.copyOf(transactionIds);
        if (transactionIds.isEmpty()) {
            throw new IllegalArgumentException("transaction_ids must not be empty");
        }
        if (transactionIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("transaction_ids must not contain blank values");
        }
        Objects.requireNonNull(status, "status must not be null");
        citedEvidence = List.copyOf(citedEvidence);
        examinedEvidence = List.copyOf(examinedEvidence);
        if (failureReason != null && !FAILURE_REASON_PATTERN.matcher(failureReason).matches()) {
            throw new IllegalArgumentException("failure_reason must be an uppercase code");
        }
        if (attempts < 0 || attempts > MAX_REASONING_CALLS) {
            throw new IllegalArgumentException("attempts must be between 0 and " + MAX_REASONING_CALLS);
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        Objects.requireNonNull(investigatedAt, "investigated_at must not be null");

        if (status == Status.FAILED) {
            if (failureReason == null) {
                throw new IllegalArgumentException("a FAILED result needs a failure_reason");
            }
            if (rootCause != null) {
                throw new IllegalArgumentException("a FAILED result has no root_cause");
            }
        } else {
            if (failureReason != null) {
                throw new IllegalArgumentException("a non-FAILED result has no failure_reason");
            }
            if (attempts < 1) {
                throw new IllegalArgumentException("a non-FAILED result needs at least one attempt");
            }
        }

        if (status == Status.RESOLVED) {
            if (rootCause == null || rootCause.isBlank()) {
                throw new IllegalArgumentException("a RESOLVED result needs a root_cause");
            }
            if (citedEvidence.isEmpty()) {
                throw new IllegalArgumentException("a RESOLVED result needs cited evidence");
            }
        }
        if (!examinedEvidence.containsAll(citedEvidence)) {
            throw new IllegalArgumentException("cited evidence must also appear in examined_evidence");
        }
    }

    public enum SourcePhase {
        PHASE_3,
        PHASE_6
    }

    public enum Status {
        RESOLVED,
        LOW_CONFIDENCE,
        FAILED
    }
}
