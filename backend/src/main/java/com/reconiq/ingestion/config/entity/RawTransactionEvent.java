package com.reconiq.ingestion.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "raw_transaction_events", schema = "ingestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RawTransactionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "reference_id")
    private String referenceId;

    @Column(name = "settlement_id")
    private String settlementId;

    @Column(name = "amount_value")
    private BigDecimal amountValue;

    @Column(name = "amount_currency")
    private String amountCurrency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "description")
    private String description;

    @Column(name = "raw_payload", columnDefinition = "jsonb", nullable = false)
    private String rawPayload;

    @Column(name = "ingested_at", insertable = false, updatable = false)
    private Instant ingestedAt;
}