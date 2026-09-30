package com.reconciliation.model;

import java.math.BigDecimal;
import java.time.Instant;

public class CanonicalTransactionRecord {

    private String eventId;
    private String transactionId;
    private String source;
    private Instant eventTimestamp;
    private String referenceId;
    private String referenceKey;
    private String transactionType;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String canonicalStatus;
    private String merchantId;
    private String customerId;
    private String settlementId;
    private String description;
    private Instant processedAt;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public String getReferenceKey() { return referenceKey; }
    public void setReferenceKey(String referenceKey) { this.referenceKey = referenceKey; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCanonicalStatus() { return canonicalStatus; }
    public void setCanonicalStatus(String canonicalStatus) { this.canonicalStatus = canonicalStatus; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getSettlementId() { return settlementId; }
    public void setSettlementId(String settlementId) { this.settlementId = settlementId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}