package com.reconciliation.service;

import com.reconciliation.model.MatchingResult;

public interface MatchingService {

    MatchingResult match(
            String gatewayTransactionId,
            String bankTransactionId,
            String gatewayReferenceId,
            String bankReferenceId,
            double gatewayAmount,
            double bankAmount,
            String gatewayCurrency,
            String bankCurrency
    );
}