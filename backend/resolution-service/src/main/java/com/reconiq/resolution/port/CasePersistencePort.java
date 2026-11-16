package com.reconiq.resolution.port;

import java.util.Optional;

public interface CasePersistencePort {

    Optional<CaseSnapshot> findById(String caseId);

    CaseSnapshot save(CaseSnapshot snapshot);
}
