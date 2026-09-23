package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

@Testcontainers
class WriterLoopTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String TOPIC = "txn.canonical";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private HikariDataSource dataSource;
    private MockConsumer<String, CanonicalTransaction> consumer;
    private MockProducer<String, String> producer;
    private WriterLoop writerLoop;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = DataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DataSourceFactory.migrate(dataSource);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE normalization.canonical_transactions");
        }

        consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(PARTITION));
        Map<TopicPartition, Long> beginning = new HashMap<>();
        beginning.put(PARTITION, 0L);
        consumer.updateBeginningOffsets(beginning);

        producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());

        writerLoop = new WriterLoop(consumer, dataSource, new UpsertRepository(), new ConflictChecker(),
                new DlqPublisher(producer, "txn.canonical.dlq"), Duration.ofMillis(100));
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private CanonicalTransaction sample(String eventId, String canonicalStatus, BigDecimal amount) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return CanonicalTransaction.newBuilder()
                .setEventId(eventId)
                .setTransactionId("TXN1")
                .setSource("PAYMENT_GATEWAY")
                .setEventTimestamp(now)
                .setReferenceId("REF1")
                .setReferenceKey("REF1")
                .setTransactionType("PAYMENT")
                .setAmountValue(amount)
                .setAmountCurrency("INR")
                .setStatus("SUCCESS")
                .setCanonicalStatus(canonicalStatus)
                .setMerchantId("MER1")
                .setCustomerId("CUST1")
                .setSettlementId("SET1")
                .setDescription(null)
                .setProcessedAt(now)
                .build();
    }

    private int rowCount() throws Exception {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM normalization.canonical_transactions")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void happyPathInsertsAndCommitsOffsets() throws Exception {
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "e1", sample("e1", "COMPLETED", new BigDecimal("10.00"))));

        writerLoop.processOnce();

        assertThat(rowCount()).isEqualTo(1);
        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset()).isEqualTo(1L);
        assertThat(producer.history()).isEmpty();
    }

    @Test
    void conflictingDuplicateGoesToDlqAndFirstRowStays() throws Exception {
        CanonicalTransaction first = sample("e2", "COMPLETED", new BigDecimal("10.00"));
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "e2", first));
        writerLoop.processOnce();

        CanonicalTransaction changed = sample("e2", "FAILED", new BigDecimal("99.00"));
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1L, "e2", changed));
        writerLoop.processOnce();

        assertThat(rowCount()).isEqualTo(1);
        assertThat(producer.history()).hasSize(1);
        assertThat(producer.history().get(0).value()).contains("EVENT_ID_CONFLICT");

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT canonical_status FROM normalization.canonical_transactions WHERE event_id='e2'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void badRowInBatchGoesToDlqAndGoodRowsStillInsert() throws Exception {
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "good1", sample("good1", "COMPLETED", new BigDecimal("10.00"))));
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 1L, "bad1", sample("bad1", "NOT_A_REAL_STATUS", new BigDecimal("5.00"))));
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 2L, "good2", sample("good2", "FAILED", new BigDecimal("20.00"))));

        writerLoop.processOnce();

        assertThat(rowCount()).isEqualTo(2);
        assertThat(producer.history()).hasSize(1);
        assertThat(producer.history().get(0).value()).contains("DB_REJECTED");
        assertThat(producer.history().get(0).key()).isEqualTo("bad1");
        assertThat(consumer.committed(java.util.Set.of(PARTITION)).get(PARTITION).offset()).isEqualTo(3L);
    }

    @Test
    void emptyPollDoesNothing() {
        writerLoop.processOnce();
        assertThat(producer.history()).isEmpty();
    }
}
