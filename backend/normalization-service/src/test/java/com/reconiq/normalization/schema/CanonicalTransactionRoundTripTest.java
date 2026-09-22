package com.reconiq.normalization.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves a nullable decimal(18,2) amount survives real Avro serialization and deserialization
 * unchanged (risk R-16). Uses a mock:// Schema Registry: no Docker, no network.
 */
class CanonicalTransactionRoundTripTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private CanonicalTransaction.Builder baseBuilder() {
        return CanonicalTransaction.newBuilder()
                .setEventId("evt_gw_00006")
                .setTransactionId("TXN00006")
                .setSource("PAYMENT_GATEWAY")
                .setEventTimestamp(NOW)
                .setReferenceId("REF00006")
                .setReferenceKey("REF00006")
                .setTransactionType("PAYMENT")
                .setAmountCurrency("INR")
                .setStatus("SUCCESS")
                .setCanonicalStatus("COMPLETED")
                .setMerchantId("MER001")
                .setCustomerId("CUST1001")
                .setSettlementId("SET00006")
                .setDescription(null)
                .setProcessedAt(NOW);
    }

    private CanonicalTransaction roundTrip(CanonicalTransaction original) {
        MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
        Map<String, Object> config = Map.of(
                "schema.registry.url", "mock://round-trip-test",
                "specific.avro.reader", true);

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(registry);
             KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(registry)) {
            serializer.configure(config, false);
            deserializer.configure(config, false);

            byte[] bytes = serializer.serialize("txn.canonical", original);
            return (CanonicalTransaction) deserializer.deserialize("txn.canonical", bytes);
        }
    }

    @Test
    void amountSurvivesTheRoundTripExactly() {
        CanonicalTransaction original = baseBuilder()
                .setAmountValue(new BigDecimal("1051.30"))
                .build();

        CanonicalTransaction result = roundTrip(original);

        assertThat(result.getAmountValue()).isEqualByComparingTo(new BigDecimal("1051.30"));
        assertThat(result.getAmountValue().scale()).isEqualTo(2);
    }

    @Test
    void nullAmountSurvivesTheRoundTrip() {
        CanonicalTransaction original = baseBuilder()
                .setAmountValue(null)
                .build();

        CanonicalTransaction result = roundTrip(original);

        assertThat(result.getAmountValue()).isNull();
    }

    @Test
    void everyOtherFieldSurvivesTheRoundTrip() {
        CanonicalTransaction original = baseBuilder()
                .setAmountValue(new BigDecimal("0.00"))
                .build();

        CanonicalTransaction result = roundTrip(original);

        assertThat(result.getEventId()).isEqualTo("evt_gw_00006");
        assertThat(result.getTransactionId()).isEqualTo("TXN00006");
        assertThat(result.getSource()).isEqualTo("PAYMENT_GATEWAY");
        assertThat(result.getEventTimestamp()).isEqualTo(NOW);
        assertThat(result.getReferenceId()).isEqualTo("REF00006");
        assertThat(result.getAmountCurrency()).isEqualTo("INR");
        assertThat(result.getCanonicalStatus()).isEqualTo("COMPLETED");
        assertThat(result.getDescription()).isNull();
        assertThat(result.getProcessedAt()).isEqualTo(NOW);
    }
}