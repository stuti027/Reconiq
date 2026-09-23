package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.input.ParseResult;
import com.reconiq.normalization.input.ParsedRawEvent;
import com.reconiq.normalization.input.RawEventParser;
import com.reconiq.normalization.mapping.BankMapper;
import com.reconiq.normalization.mapping.GatewayMapper;
import com.reconiq.normalization.mapping.MapResult;
import com.reconiq.normalization.mapping.MapperRegistry;
import com.reconiq.normalization.rules.StatusMapping;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs all 197 real sample rows through WriterLoop, then replays the identical batch: the table must be
 * unchanged and 0 EVENT_ID_CONFLICT records produced (plan 2.5's "replay is a no-op" expected number).
 */
@Testcontainers
class FullSampleWriterIdempotencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String TOPIC = "txn.canonical";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final String STATUS_CSV = "source,raw_status,canonical_status\n"
            + "PAYMENT_GATEWAY,SUCCESS,COMPLETED\n"
            + "PAYMENT_GATEWAY,FAILED,FAILED\n"
            + "BANK,SETTLED,COMPLETED\n"
            + "BANK,FAILED,FAILED\n";

    private HikariDataSource dataSource;
    private List<CanonicalTransaction> allRows;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = DataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DataSourceFactory.migrate(dataSource);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE normalization.canonical_transactions");
        }

        StatusMapping statusMapping = StatusMapping.fromReader(new StringReader(STATUS_CSV));
        RawEventParser parser = new RawEventParser(Set.of("BANK", "PAYMENT_GATEWAY"));
        MapperRegistry mapperRegistry = new MapperRegistry(
                List.of(new GatewayMapper(statusMapping), new BankMapper(statusMapping)));

        ObjectMapper mapper = new ObjectMapper();
        allRows = new ArrayList<>();
        for (String line : readFixtureLines("wire-events.jsonl")) {
            ParseResult result = parser.parse(line);
            ParsedRawEvent parsed = (ParsedRawEvent) result;
            MapResult mapped = mapperRegistry.get(parsed.source()).orElseThrow().map(parsed);
            allRows.add(mapped.transaction());
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private List<String> readFixtureLines(String resourceName) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + resourceName)) {
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

    private int rowCount() throws Exception {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM normalization.canonical_transactions")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private WriterLoop newWriterLoop(MockConsumer<String, CanonicalTransaction> consumer, MockProducer<String, String> producer) {
        return new WriterLoop(consumer, dataSource, new UpsertRepository(), new ConflictChecker(),
                new DlqPublisher(producer, "txn.canonical.dlq"), Duration.ofMillis(100));
    }

    @Test
    void writingAllSampleRowsTwiceLeavesTheTableUnchangedWithZeroConflicts() throws Exception {
        // First pass: all 197 rows into a fresh table.
        MockConsumer<String, CanonicalTransaction> firstConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        firstConsumer.assign(List.of(PARTITION));
        Map<TopicPartition, Long> beginning = new HashMap<>();
        beginning.put(PARTITION, 0L);
        firstConsumer.updateBeginningOffsets(beginning);
        MockProducer<String, String> firstProducer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());

        long offset = 0;
        for (CanonicalTransaction t : allRows) {
            firstConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, offset++, t.getEventId(), t));
        }
        newWriterLoop(firstConsumer, firstProducer).processOnce();

        assertThat(rowCount()).isEqualTo(197);
        assertThat(firstProducer.history()).as("first pass should produce no DLQ records").isEmpty();

        // Second pass: the identical 197 rows again, fresh consumer (simulates a replay from offset 0).
        MockConsumer<String, CanonicalTransaction> secondConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        secondConsumer.assign(List.of(PARTITION));
        secondConsumer.updateBeginningOffsets(beginning);
        MockProducer<String, String> secondProducer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());

        offset = 0;
        for (CanonicalTransaction t : allRows) {
            secondConsumer.addRecord(new ConsumerRecord<>(TOPIC, 0, offset++, t.getEventId(), t));
        }
        newWriterLoop(secondConsumer, secondProducer).processOnce();

        assertThat(rowCount()).as("replay must not change the row count").isEqualTo(197);
        assertThat(secondProducer.history()).as("replay must produce 0 EVENT_ID_CONFLICT records").isEmpty();
    }
}
