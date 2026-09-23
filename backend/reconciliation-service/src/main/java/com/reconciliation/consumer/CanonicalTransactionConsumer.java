package com.reconciliation.consumer;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import com.reconciliation.service.CanonicalTransactionMapper;
import com.reconciliation.service.MatchingStateService;
import com.reconciliation.service.ReconciliationProcessor;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CanonicalTransactionConsumer {

    private final CanonicalTransactionMapper mapper;
    private final MatchingStateService matchingStateService;
    private final ReconciliationProcessor reconciliationProcessor;

    public CanonicalTransactionConsumer() {
        this.mapper = new CanonicalTransactionMapper();
        this.matchingStateService = new MatchingStateService();
        this.reconciliationProcessor = new ReconciliationProcessor();
    }

    @KafkaListener(
            topics = "txn.canonical",
            groupId = "reconciliation-service"
    )
    public void consume(
            ConsumerRecord<String, GenericRecord> record) {

        CanonicalTransactionRecord transaction =
                mapper.map(record.value());

        List<MatchingResult> results =
                reconciliationProcessor.process(
                        transaction,
                        matchingStateService.getState()
                );

        for (MatchingResult result : results) {

            System.out.println(
                    "Reconciliation result: " +
                    result.getStatus() +
                    " | Strategy: " +
                    result.getMatchingStrategy() +
                    " | Gateway: " +
                    result.getGatewayTransactionId() +
                    " | Bank: " +
                    result.getBankTransactionId()
            );
        }

        matchingStateService.addTransaction(transaction);
    }
}