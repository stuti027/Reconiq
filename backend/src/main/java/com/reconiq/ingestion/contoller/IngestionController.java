package com.reconiq.ingestion.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.ingestion.dto.TransactionEventDto;
import com.reconiq.ingestion.entity.RawTransactionEvent;
import com.reconiq.ingestion.repository.RawTransactionEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

    private final KafkaTemplate<String, TransactionEventDto> kafkaTemplate;
    private final RawTransactionEventRepository repository;
    private final ObjectMapper objectMapper;
    private static final String KAFKA_TOPIC = "raw-transactions";

    public IngestionController(KafkaTemplate<String, TransactionEventDto> kafkaTemplate,
                               RawTransactionEventRepository repository,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/event")
    public ResponseEntity<String> ingestEvent(@RequestBody TransactionEventDto event) {
        // Idempotency Check: Prevent duplicate processing of the same event_id
        if (repository.existsByEventId(event.eventId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("Duplicate event ignored: " + event.eventId());
        }

        try {
            var txn = (event.data() != null) ? event.data().transaction() : null;

            // 1. Save Raw Event to Postgres DB
            RawTransactionEvent entity = RawTransactionEvent.builder()
                    .eventId(event.eventId())
                    .eventType(event.eventType())
                    .source(event.source())
                    .eventTimestamp(event.eventTimestamp())
                    .transactionId(txn != null ? txn.transactionId() : "UNKNOWN")
                    .referenceId(txn != null ? txn.referenceId() : null)
                    .settlementId(txn != null ? txn.settlementId() : null)
                    .amountValue(txn != null && txn.amount() != null ? txn.amount().value() : null)
                    .amountCurrency(txn != null && txn.amount() != null ? txn.amount().currency() : null)
                    .status(txn != null ? txn.status() : "UNKNOWN")
                    .transactionType(txn != null ? txn.transactionType() : "UNKNOWN")
                    .merchantId(txn != null ? txn.merchantId() : "UNKNOWN")
                    .customerId(txn != null ? txn.customerId() : "UNKNOWN")
                    .description(txn != null ? txn.description() : null)
                    .rawPayload(objectMapper.writeValueAsString(event))
                    .build();

            repository.save(entity);

            // 2. Publish to Kafka
            String key = (txn != null) ? txn.transactionId() : event.eventId();
            kafkaTemplate.send(KAFKA_TOPIC, key, event);

            return ResponseEntity.ok("Event ingested and published to Kafka: " + event.eventId());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error ingesting event: " + e.getMessage());
        }
    }
}