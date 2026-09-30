package com.reconciliation.consumer;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.model.MatchingResult;
import com.reconciliation.service.CanonicalTransactionMapper;
import com.reconciliation.service.MatchingStateService;
import com.reconciliation.service.ReconciliationProcessor;
import com.reconciliation.model.MatchingState;

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

    public CanonicalTransactionConsumer(
            MatchingStateService matchingStateService) {

        this.mapper = new CanonicalTransactionMapper();
        this.matchingStateService = matchingStateService;
        this.reconciliationProcessor =
                new ReconciliationProcessor();
    }

    @KafkaListener(
            topics = "${reconciliation.kafka.topic:txn.canonical}",
            groupId = "${spring.kafka.consumer.group-id:reconciliation-service}"
    )
    public void consume(
            ConsumerRecord<String, GenericRecord> record) {

        if (record == null || record.value() == null) {
            return;
        }

        CanonicalTransactionRecord transaction =
                mapper.map(record.value());

        if (transaction == null) {
            return;
        }

        MatchingState state = matchingStateService.getState();

        List<MatchingResult> results =
                reconciliationProcessor.process(
                        transaction,
                        state
                );

        matchingStateService.persistReconciled(state);

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