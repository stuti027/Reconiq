package com.reconiq.normalization.topology;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.input.RawEventParser;
import com.reconiq.normalization.mapping.BankMapper;
import com.reconiq.normalization.mapping.GatewayMapper;
import com.reconiq.normalization.mapping.MapperRegistry;
import com.reconiq.normalization.rules.StatusMapping;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;

class TopologyFactoryTest {

    private static final String INPUT = "raw-transactions";
    private static final String CANONICAL = "txn.canonical";
    private static final String DLQ = "txn.canonical.dlq";
    private static final String CSV = "source,raw_status,canonical_status\n"
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    private static final String VALID_GATEWAY = """
            {"event_id":"evt1","event_timestamp":1787219280.0,"source":"PAYMENT_GATEWAY",
             "data":{"transaction":{"transaction_id":"TXN1","reference_id":"REF1",
               "merchant_id":"MER1","customer_id":"CUST1","transaction_type":"PAYMENT",
               "settlement_id":"SET1","amount":{"value":10.00,"currency":"INR"},
               "timestamp":1787219280.0,"status":"SUCCESS","description":null}}}
            """;

    private static final String VALID_BANK_UNMAPPED_STATUS = """
            {"event_id":"evt2","event_timestamp":1787219280.0,"source":"BANK",
             "data":{"transaction":{"transaction_id":"TXN2","reference_id":"REF2",
               "merchant_id":"MER1","customer_id":"CUST1","transaction_type":"PAYMENT",
               "settlement_id":"SET2","amount":{"value":5.00,"currency":"INR"},
               "timestamp":1787219280.0,"status":"WEIRD","description":null}}}
            """;

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> input;
    private TestOutputTopic<String, CanonicalTransaction> canonicalOutput;
    private TestOutputTopic<String, String> dlqOutput;

    @BeforeEach
    void setUp() {
        StatusMapping statusMapping = StatusMapping.fromReader(new StringReader(CSV));
        RawEventParser parser = new RawEventParser(Set.of("BANK", "PAYMENT_GATEWAY"));
        MapperRegistry mapperRegistry = new MapperRegistry(
                List.of(new GatewayMapper(statusMapping), new BankMapper(statusMapping)));

        MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
        Serde<CanonicalTransaction> canonicalSerde = new SpecificAvroSerde<>(registry);
        canonicalSerde.configure(Map.of("schema.registry.url", "mock://topology-test"), false);

        Topology topology = new TopologyFactory(List.of(INPUT), CANONICAL, DLQ, parser, mapperRegistry, canonicalSerde)
                .build();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "topology-test");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:0000");

        driver = new TopologyTestDriver(topology, props);
        input = driver.createInputTopic(INPUT, Serdes.String().serializer(), Serdes.String().serializer());
        canonicalOutput = driver.createOutputTopic(CANONICAL, Serdes.String().deserializer(), canonicalSerde.deserializer());
        dlqOutput = driver.createOutputTopic(DLQ, Serdes.String().deserializer(), Serdes.String().deserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void validGatewayEventGoesToCanonicalTopicOnly() {
        input.pipeInput("TXN1", VALID_GATEWAY);

        assertThat(canonicalOutput.isEmpty()).isFalse();
        var record = canonicalOutput.readKeyValue();
        assertThat(record.key).isEqualTo("evt1");
        assertThat(record.value.getTransactionId()).isEqualTo("TXN1");
        assertThat(record.value.getCanonicalStatus()).isEqualTo("COMPLETED");
        assertThat(canonicalOutput.isEmpty()).isTrue();
        assertThat(dlqOutput.isEmpty()).isTrue();
    }

    @Test
    void unmappedStatusGoesToBothCanonicalAndFlaggedDlq() throws Exception {
        input.pipeInput("TXN2", VALID_BANK_UNMAPPED_STATUS);

        var canonical = canonicalOutput.readKeyValue();
        assertThat(canonical.value.getCanonicalStatus()).isEqualTo("UNKNOWN");
        assertThat(canonicalOutput.isEmpty()).isTrue();

        assertThat(dlqOutput.isEmpty()).isFalse();
        String dlqJson = dlqOutput.readValue();
        JsonNode node = new ObjectMapper().readTree(dlqJson);
        assertThat(node.get("kind").asText()).isEqualTo("FLAGGED");
        assertThat(node.get("reasonCode").asText()).isEqualTo("UNMAPPED_STATUS");
        assertThat(node.get("originalKey").asText()).isEqualTo("TXN2");
    }

    @Test
    void invalidJsonGoesToDlqOnlyWithSourceTopicAndOffset() throws Exception {
        input.pipeInput("bad-key", "not json");

        assertThat(canonicalOutput.isEmpty()).isTrue();
        String dlqJson = dlqOutput.readValue();
        JsonNode node = new ObjectMapper().readTree(dlqJson);
        assertThat(node.get("kind").asText()).isEqualTo("REJECTED");
        assertThat(node.get("reasonCode").asText()).isEqualTo("INVALID_JSON");
        assertThat(node.get("sourceTopic").asText()).isEqualTo(INPUT);
        assertThat(node.get("originalKey").asText()).isEqualTo("bad-key");
        assertThat(node.get("originalValue").asText()).isEqualTo("not json");
    }

    @Test
    void unknownSourceGoesToDlqWithUnknownSourceReason() throws Exception {
        String json = VALID_GATEWAY.replace("PAYMENT_GATEWAY", "CARRIER_PIGEON");
        input.pipeInput("bad-src", json);

        assertThat(canonicalOutput.isEmpty()).isTrue();
        JsonNode node = new ObjectMapper().readTree(dlqOutput.readValue());
        assertThat(node.get("reasonCode").asText()).isEqualTo("UNKNOWN_SOURCE");
    }
}