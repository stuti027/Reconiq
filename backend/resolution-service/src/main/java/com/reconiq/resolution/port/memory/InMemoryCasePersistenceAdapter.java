package com.reconiq.resolution.port.memory;

import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.CaseSnapshot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class InMemoryCasePersistenceAdapter implements CasePersistencePort {

    private final ConcurrentMap<String, CaseSnapshot> cases = new ConcurrentHashMap<>();

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
