package com.reconiq.ingestion.controller;

import com.reconiq.ingestion.dto.TransactionEventDto;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestionController {

    private final KafkaTemplate<String, TransactionEventDto> kafkaTemplate;
    private static final String KAFKA_TOPIC = "raw-transactions";

    public IngestionController(KafkaTemplate<String, TransactionEventDto> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/event")
    public ResponseEntity<String> ingestEvent(@RequestBody TransactionEventDto event) {
        String key = (event.data() != null && event.data().transaction() != null) 
                ? event.data().transaction().transactionId() 
                : event.eventId();

        kafkaTemplate.send(KAFKA_TOPIC, key, event);
        return ResponseEntity.ok("Event successfully ingested and streamed to Kafka: " + event.eventId());
    }
}