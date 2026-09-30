package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalTransactionMapperTest {

    @Test
    void shouldMapCanonicalTransaction() {

        String schemaJson = """
                {
                  "type": "record",
                  "name": "CanonicalTransaction",
                  "namespace": "com.reconiq.canonical",
                  "fields": [
                    {"name": "event_id", "type": "string"},
                    {"name": "transaction_id", "type": "string"},
                    {"name": "source", "type": "string"},
                    {"name": "event_timestamp", "type": "long"},
                    {"name": "reference_id", "type": ["null", "string"], "default": null},
                    {"name": "reference_key", "type": ["null", "string"], "default": null},
                    {"name": "transaction_type", "type": ["null", "string"], "default": null},
                    {"name": "amount_value", "type": ["null", "bytes"], "default": null},
                    {"name": "amount_currency", "type": ["null", "string"], "default": null},
                    {"name": "status", "type": ["null", "string"], "default": null},
                    {"name": "canonical_status", "type": "string"},
                    {"name": "merchant_id", "type": ["null", "string"], "default": null},
                    {"name": "customer_id", "type": ["null", "string"], "default": null},
                    {"name": "settlement_id", "type": ["null", "string"], "default": null},
                    {"name": "description", "type": ["null", "string"], "default": null},
                    {"name": "processed_at", "type": "long"}
                  ]
                }
                """;

        Schema schema = new Schema.Parser().parse(schemaJson);

        GenericRecord record = new GenericData.Record(schema);

        record.put("event_id", "EVT001");
        record.put("transaction_id", "TXN001");
        record.put("source", "PAYMENT_GATEWAY");
        record.put("event_timestamp", 1756104720000L);
        record.put("reference_id", "REF001");
        record.put("reference_key", "REF001");
        record.put("transaction_type", "PAYMENT");

        BigInteger unscaledAmount = BigInteger.valueOf(100000);
        record.put(
                "amount_value",
                ByteBuffer.wrap(unscaledAmount.toByteArray())
        );

        record.put("amount_currency", "INR");
        record.put("status", "SUCCESS");
        record.put("canonical_status", "COMPLETED");
        record.put("merchant_id", "MERCHANT001");
        record.put("customer_id", "CUSTOMER001");
        record.put("settlement_id", null);
        record.put("description", "Test transaction");
        record.put("processed_at", 1756104730000L);

        CanonicalTransactionMapper mapper =
                new CanonicalTransactionMapper();

        CanonicalTransactionRecord result =
                mapper.map(record);

        assertEquals("EVT001", result.getEventId());
        assertEquals("TXN001", result.getTransactionId());
        assertEquals("PAYMENT_GATEWAY", result.getSource());
        assertEquals("REF001", result.getReferenceId());
        assertEquals("REF001", result.getReferenceKey());
        assertEquals("INR", result.getCurrency());

        assertEquals(
                new BigDecimal("1000.00"),
                result.getAmount()
        );

        assertEquals(
                "COMPLETED",
                result.getCanonicalStatus()
        );

        assertEquals(
                "MERCHANT001",
                result.getMerchantId()
        );

        assertEquals(
                "CUSTOMER001",
                result.getCustomerId()
        );

        assertEquals(
                "Test transaction",
                result.getDescription()
        );

        assertEquals(
                1756104720000L,
                result.getEventTimestamp().toEpochMilli()
        );

        assertEquals(
                1756104730000L,
                result.getProcessedAt().toEpochMilli()
        );
    }

    @Test
    void shouldHandleNullableFields() {

        String schemaJson = """
                {
                  "type": "record",
                  "name": "CanonicalTransaction",
                  "namespace": "com.reconiq.canonical",
                  "fields": [
                    {"name": "event_id", "type": "string"},
                    {"name": "transaction_id", "type": "string"},
                    {"name": "source", "type": "string"},
                    {"name": "event_timestamp", "type": "long"},
                    {"name": "reference_id", "type": ["null", "string"], "default": null},
                    {"name": "reference_key", "type": ["null", "string"], "default": null},
                    {"name": "transaction_type", "type": ["null", "string"], "default": null},
                    {"name": "amount_value", "type": ["null", "bytes"], "default": null},
                    {"name": "amount_currency", "type": ["null", "string"], "default": null},
                    {"name": "status", "type": ["null", "string"], "default": null},
                    {"name": "canonical_status", "type": "string"},
                    {"name": "merchant_id", "type": ["null", "string"], "default": null},
                    {"name": "customer_id", "type": ["null", "string"], "default": null},
                    {"name": "settlement_id", "type": ["null", "string"], "default": null},
                    {"name": "description", "type": ["null", "string"], "default": null},
                    {"name": "processed_at", "type": "long"}
                  ]
                }
                """;

        Schema schema = new Schema.Parser().parse(schemaJson);

        GenericRecord record = new GenericData.Record(schema);

        record.put("event_id", "EVT002");
        record.put("transaction_id", "TXN002");
        record.put("source", "BANK");
        record.put("event_timestamp", 1756104720000L);
        record.put("reference_id", null);
        record.put("reference_key", null);
        record.put("transaction_type", "SETTLEMENT");
        record.put("amount_value", null);
        record.put("amount_currency", null);
        record.put("status", null);
        record.put("canonical_status", "UNKNOWN");
        record.put("merchant_id", null);
        record.put("customer_id", null);
        record.put("settlement_id", "SET001");
        record.put("description", null);
        record.put("processed_at", 1756104730000L);

        CanonicalTransactionMapper mapper =
                new CanonicalTransactionMapper();

        CanonicalTransactionRecord result =
                mapper.map(record);

        assertEquals("EVT002", result.getEventId());
        assertEquals("BANK", result.getSource());
        assertNull(result.getReferenceId());
        assertNull(result.getReferenceKey());
        assertNull(result.getAmount());
        assertNull(result.getCurrency());
        assertNull(result.getMerchantId());
        assertNull(result.getCustomerId());
        assertEquals("SET001", result.getSettlementId());
        assertNull(result.getDescription());
    }
}