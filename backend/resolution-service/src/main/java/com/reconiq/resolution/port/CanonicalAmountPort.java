package com.reconiq.resolution.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface CanonicalAmountPort {

    Optional<BigDecimal> findDiscrepancyAmount(List<String> transactionIds);
}
