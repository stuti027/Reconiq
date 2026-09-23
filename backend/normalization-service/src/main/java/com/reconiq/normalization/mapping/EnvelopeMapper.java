package com.reconiq.normalization.mapping;

import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.input.ParsedRawEvent;
import com.reconiq.normalization.rules.NormalizationRules;
import com.reconiq.normalization.rules.StatusMapping;
import java.time.Instant;

public abstract class EnvelopeMapper implements SourceMapper {

    private final StatusMapping statusMapping;

    protected EnvelopeMapper(StatusMapping statusMapping) {
        this.statusMapping = statusMapping;
    }

    @Override
    public MapResult map(ParsedRawEvent event) {
        boolean unmapped = statusMapping.lookup(event.source(), event.status()).isEmpty();
        String canonicalStatus = statusMapping.canonicalStatus(event.source(), event.status());

        CanonicalTransaction transaction = CanonicalTransaction.newBuilder()
                .setEventId(NormalizationRules.clean(event.eventId()))
                .setTransactionId(NormalizationRules.clean(event.transactionId()))
                .setSource(NormalizationRules.cleanUpper(event.source()))
                .setEventTimestamp(event.eventTimestamp())
                .setReferenceId(NormalizationRules.clean(event.referenceId()))
                .setReferenceKey(NormalizationRules.referenceKey(event.referenceId()))
                .setTransactionType(NormalizationRules.cleanUpper(event.transactionType()))
                .setAmountValue(event.amountValue())
                .setAmountCurrency(NormalizationRules.cleanUpper(event.amountCurrency()))
                .setStatus(NormalizationRules.clean(event.status()))
                .setCanonicalStatus(canonicalStatus)
                .setMerchantId(NormalizationRules.clean(event.merchantId()))
                .setCustomerId(NormalizationRules.clean(event.customerId()))
                .setSettlementId(NormalizationRules.clean(event.settlementId()))
                .setDescription(NormalizationRules.clean(event.description()))
                .setProcessedAt(Instant.now())
                .build();

        return new MapResult(transaction, unmapped);
    }
}