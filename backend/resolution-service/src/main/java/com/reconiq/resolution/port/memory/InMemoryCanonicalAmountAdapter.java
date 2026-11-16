package com.reconiq.resolution.port.memory;

import com.reconiq.resolution.port.CanonicalAmountPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

final class InMemoryCanonicalAmountAdapter implements CanonicalAmountPort {

    @Override
    public Optional<BigDecimal> findDiscrepancyAmount(List<String> transactionIds) {
        return Optional.empty();
    }
}
