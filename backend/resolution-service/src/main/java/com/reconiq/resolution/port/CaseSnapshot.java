package com.reconiq.resolution.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record CaseSnapshot(
        String caseId,
        CaseState state,
        List<String> transactionIds,
        BigDecimal discrepancyAmount,
        BigDecimal anomalyScore) {

    public CaseSnapshot {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        transactionIds = List.copyOf(transactionIds);
        Objects.requireNonNull(discrepancyAmount, "discrepancyAmount must not be null");
        Objects.requireNonNull(anomalyScore, "anomalyScore must not be null");
    }
}
