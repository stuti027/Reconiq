package com.reconiq.normalization.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.normalization.dlq.DlqRecord;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

public class DlqPublisher {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Producer<String, String> producer;
    private final String dlqTopic;

    public DlqPublisher(Producer<String, String> producer, String dlqTopic) {
        this.producer = producer;
        this.dlqTopic = dlqTopic;
    }

    /** Sends synchronously (must succeed before offsets are committed). */
    public void publish(DlqRecord record) {
        try {
            String json = JSON.writeValueAsString(record);
            producer.send(new ProducerRecord<>(dlqTopic, record.originalKey(), json)).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to publish DLQ record for key " + record.originalKey(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing DLQ record", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize DLQ record for key " + record.originalKey(), e);
        }
    }
}