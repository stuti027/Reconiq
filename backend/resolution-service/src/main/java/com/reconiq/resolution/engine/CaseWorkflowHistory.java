package com.reconiq.resolution.engine;

import com.reconiq.resolution.port.CaseSnapshot;
import com.reconiq.resolution.port.TransitionRecord;

import java.util.List;
import java.util.Objects;

public record CaseWorkflowHistory(
        CaseSnapshot snapshot,
        List<TransitionRecord> auditEntries) {

    public CaseWorkflowHistory {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        auditEntries = List.copyOf(auditEntries);
    }
}
