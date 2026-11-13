package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.normalization.dlq.DlqRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class DlqPublisherTest {

    @Test
    void publishesJsonRecordKeyedByOriginalKey() throws Exception {
        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        DlqPublisher publisher = new DlqPublisher(producer, "txn.canonical.dlq");

        DlqRecord record = new DlqRecord("REJECTED", "DB_REJECTED", "numeric field overflow",
                "TXN1", "{}", "txn.canonical", 0, 5L, 1000L, 2000L, "normalization-service");
        publisher.publish(record);

        assertThat(producer.history()).hasSize(1);
        var sent = producer.history().get(0);
        assertThat(sent.topic()).isEqualTo("txn.canonical.dlq");
        assertThat(sent.key()).isEqualTo("TXN1");

        JsonNode node = new ObjectMapper().readTree(sent.value());
        assertThat(node.get("reasonCode").asText()).isEqualTo("DB_REJECTED");
    }
}