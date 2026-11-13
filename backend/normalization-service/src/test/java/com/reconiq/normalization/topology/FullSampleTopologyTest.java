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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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

/**
 * Runs the whole topology against every real sample event (closes register gap G-A: a bug that hits
 * every message must be caught before this test passes) and against the curated bad-message fixture.
 * Expected numbers are computed independently from sample_data.json, not copied from the code under test.
 */
class FullSampleTopologyTest {

    private static final String INPUT = "raw-transactions";
    private static final String CANONICAL = "txn.canonical";
    private static final String DLQ = "txn.canonical.dlq";
    private static final String STATUS_CSV = "source,raw_status,canonical_status\n"
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> input;
    private TestOutputTopic<String, CanonicalTransaction> canonicalOutput;
    private TestOutputTopic<String, String> dlqOutput;

    @BeforeEach
    void setUp() {
        StatusMapping statusMapping = StatusMapping.fromReader(new java.io.StringReader(STATUS_CSV));
        RawEventParser parser = new RawEventParser(Set.of("BANK", "PAYMENT_GATEWAY"));
        MapperRegistry mapperRegistry = new MapperRegistry(
                List.of(new GatewayMapper(statusMapping), new BankMapper(statusMapping)));

        MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
        Serde<CanonicalTransaction> canonicalSerde = new SpecificAvroSerde<>(registry);
        canonicalSerde.configure(Map.of("schema.registry.url", "mock://full-sample-test"), false);

        Topology topology = new TopologyFactory(List.of(INPUT), CANONICAL, DLQ, parser, mapperRegistry, canonicalSerde)
                .build();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "full-sample-test");
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

    private List<String> readFixtureLines(String resourceName) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + resourceName)) {
            if (stream == null) {
                throw new IllegalStateException("Fixture not found: " + resourceName
                        + " (run scripts/generate-fixtures.js first)");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    @Test
    void allSampleWireEventsProduceExpectedCanonicalRowsAndZeroDlq() throws Exception {
        List<String> lines = readFixtureLines("wire-events.jsonl");
        ObjectMapper mapper = new ObjectMapper();

        for (String line : lines) {
            JsonNode event = mapper.readTree(line);
            input.pipeInput(event.get("event_id").asText(), line);
        }

        List<CanonicalTransaction> canonicalRows = canonicalOutput.readValuesToList();
        assertThat(canonicalRows).hasSize(197);
        assertThat(dlqOutput.isEmpty()).as("expected 0 DLQ records for the clean sample").isTrue();

        Map<String, Long> bySource = new HashMap<>();
        Map<String, Long> byStatus = new HashMap<>();
        Map<String, Long> byCurrency = new HashMap<>();
        Map<String, Long> byTransactionId = new HashMap<>();
        long withDescription = 0;
        long withoutAmount = 0;

        for (CanonicalTransaction t : canonicalRows) {
            bySource.merge(t.getSource(), 1L, Long::sum);
            byStatus.merge(t.getCanonicalStatus(), 1L, Long::sum);
            byCurrency.merge(String.valueOf(t.getAmountCurrency()), 1L, Long::sum);
            byTransactionId.merge(t.getTransactionId(), 1L, Long::sum);
            if (t.getDescription() != null) {
                withDescription++;
            }
            if (t.getAmountValue() == null) {
                withoutAmount++;
            }
            if (t.getAmountValue() != null) {
                assertThat(t.getAmountValue().scale()).as("amount scale for %s", t.getEventId()).isEqualTo(2);
            }
        }

        assertThat(bySource).containsEntry("PAYMENT_GATEWAY", 106L).containsEntry("BANK", 91L);
        assertThat(byStatus).containsEntry("COMPLETED", 196L).containsEntry("FAILED", 1L);
        assertThat(byStatus.getOrDefault("UNKNOWN", 0L)).isZero();
        assertThat(byCurrency).containsEntry("INR", 195L).containsEntry("USD", 1L).containsEntry("null", 1L);
        assertThat(withDescription).isEqualTo(1);
        assertThat(withoutAmount).isEqualTo(1);

        long twoRowTransactions = byTransactionId.values().stream().filter(c -> c == 2).count();
        long oneRowTransactions = byTransactionId.values().stream().filter(c -> c == 1).count();
        assertThat(twoRowTransactions).isEqualTo(81);
        assertThat(oneRowTransactions).isEqualTo(35);
    }

    @Test
    void curatedBadMessagesEachProduceExactlyOneDlqRecordWithTheExpectedReason() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> lines = readFixtureLines("bad-messages.jsonl");

        int i = 0;
        for (String line : lines) {
            JsonNode wrapper = mapper.readTree(line);
            String expectedReason = wrapper.get("reasonCode").asText();
            String raw = wrapper.get("raw").asText();

            input.pipeInput("bad-" + (i++), raw);

            assertThat(canonicalOutput.isEmpty()).as("bad message should not produce a canonical row").isTrue();
            assertThat(dlqOutput.isEmpty()).as("expected one DLQ record for: %s", raw).isFalse();
            String dlqJson = dlqOutput.readValue();
            JsonNode dlqRecord = mapper.readTree(dlqJson);
            assertThat(dlqRecord.get("reasonCode").asText()).as("raw=%s", raw).isEqualTo(expectedReason);
        }
        assertThat(dlqOutput.isEmpty()).isTrue();
    }

    @Test
    void anUnmappedStatusProducesOneUnknownCanonicalRowPlusOneFlaggedDlqRecord() throws Exception {
        String event = """
                {"event_id":"evt_refund_test","event_timestamp":1787219280,
                 "source":"PAYMENT_GATEWAY","data":{"transaction":{"transaction_id":"TXN-REFUND-TEST",
                 "reference_id":"REF-REFUND-TEST","amount":{"value":10.00,"currency":"INR"},
                 "timestamp":1787219280,"status":"REFUNDED","merchant_id":"MER1","customer_id":"CUST1",
                 "transaction_type":"PAYMENT","settlement_id":"SET-REFUND-TEST"}}}
                """;

        input.pipeInput("TXN-REFUND-TEST", event);

        CanonicalTransaction row = canonicalOutput.readValue();
        assertThat(row.getCanonicalStatus()).isEqualTo("UNKNOWN");
        assertThat(canonicalOutput.isEmpty()).isTrue();

        String dlqJson = dlqOutput.readValue();
        JsonNode dlqRecord = new ObjectMapper().readTree(dlqJson);
        assertThat(dlqRecord.get("kind").asText()).isEqualTo("FLAGGED");
        assertThat(dlqRecord.get("reasonCode").asText()).isEqualTo("UNMAPPED_STATUS");
        assertThat(dlqOutput.isEmpty()).isTrue();
    }
}
