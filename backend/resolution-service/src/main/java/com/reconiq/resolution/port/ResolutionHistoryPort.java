package com.reconiq.resolution.port;

import java.util.List;

public interface ResolutionHistoryPort {

    void recordTransition(TransitionRecord transition);

    List<TransitionRecord> findByCaseId(String caseId);
}
