package com.reconiq.resolution.port.memory;

import com.reconiq.resolution.port.ResolutionHistoryPort;
import com.reconiq.resolution.port.TransitionRecord;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class InMemoryResolutionHistoryAdapter implements ResolutionHistoryPort {

    private final ConcurrentMap<String, ConcurrentLinkedQueue<TransitionRecord>> transitions =
            new ConcurrentHashMap<>();

    @Override
    public void recordTransition(TransitionRecord transition) {
        transitions.computeIfAbsent(transition.caseId(), ignored -> new ConcurrentLinkedQueue<>())
                .add(transition);
    }

    @Override
    public List<TransitionRecord> findByCaseId(String caseId) {
        var caseTransitions = transitions.get(caseId);
        return caseTransitions == null ? List.of() : List.copyOf(caseTransitions);
    }
}
