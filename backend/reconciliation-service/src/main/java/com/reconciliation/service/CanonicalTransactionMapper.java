package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;

import org.apache.avro.generic.GenericRecord;
import org.apache.avro.util.Utf8;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;

public class CanonicalTransactionMapper {

    public CanonicalTransactionRecord map(GenericRecord record) {

        CanonicalTransactionRecord transaction =
                new CanonicalTransactionRecord();

        transaction.setEventId(
                getString(record, "event_id")
        );

        transaction.setTransactionId(
                getString(record, "transaction_id")
        );

        transaction.setSource(
                getString(record, "source")
        );

        transaction.setEventTimestamp(
                getInstant(record, "event_timestamp")
        );

        transaction.setReferenceId(
                getString(record, "reference_id")
        );

        transaction.setReferenceKey(
                getString(record, "reference_key")
        );

        transaction.setTransactionType(
                getString(record, "transaction_type")
        );

        transaction.setAmount(
                getDecimal(record, "amount_value")
        );

        transaction.setCurrency(
                getString(record, "amount_currency")
        );

        transaction.setStatus(
                getString(record, "status")
        );

        transaction.setCanonicalStatus(
                getString(record, "canonical_status")
        );

        transaction.setMerchantId(
                getString(record, "merchant_id")
        );

        transaction.setCustomerId(
                getString(record, "customer_id")
        );

        transaction.setSettlementId(
                getString(record, "settlement_id")
        );

        transaction.setDescription(
                getString(record, "description")
        );

        transaction.setProcessedAt(
                getInstant(record, "processed_at")
        );

        return transaction;
    }

    private String getString(GenericRecord record, String field) {

        Object value = record.get(field);

        if (value == null) {
            return null;
        }

        if (value instanceof Utf8) {
            return value.toString();
        }

        return value.toString();
    }

    private Instant getInstant(GenericRecord record, String field) {

        Object value = record.get(field);

        if (value == null) {
            return null;
        }

        long timestamp = ((Number) value).longValue();

        return Instant.ofEpochMilli(timestamp);
    }

    private BigDecimal getDecimal(GenericRecord record, String field) {

        Object value = record.get(field);

        if (value == null) {
            return null;
        }

        ByteBuffer buffer = (ByteBuffer) value;

        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        return new BigDecimal(
                new java.math.BigInteger(bytes),
                2
        );
    }
}