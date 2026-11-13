package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
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
 * Reproduces the real bug found in the sandbox run (build-challenges-log.md): a failure to even obtain a
 * database connection (not just a failure during a query) must go through the same environment-outage
 * retry path as any other environment error, not kill the writer thread silently.
 */
@Testcontainers
class WriterLoopConnectionFailureTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String TOPIC = "txn.canonical";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private HikariDataSource realDataSource;

    @BeforeEach
    void setUp() throws Exception {
        realDataSource = DataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DataSourceFactory.migrate(realDataSource);
        try (Connection conn = realDataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE normalization.canonical_transactions");
        }
    }

    @AfterEach
    void tearDown() {
        realDataSource.close();
    }

    /** Fails getConnection() with a connection-unavailable SQLState the first N times, then delegates for real. */
    private static class FlakyDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicInteger failuresRemaining;

        FlakyDataSource(DataSource delegate, int failures) {
            this.delegate = delegate;
            this.failuresRemaining = new AtomicInteger(failures);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (failuresRemaining.getAndDecrement() > 0) {
                throw new SQLException("simulated: connection is not available, request timed out", "08001");
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    private CanonicalTransaction sample(String eventId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return CanonicalTransaction.newBuilder()
                .setEventId(eventId)
                .setTransactionId("TXN1")
                .setSource("PAYMENT_GATEWAY")
                .setEventTimestamp(now)
                .setReferenceId("REF1")
                .setReferenceKey("REF1")
                .setTransactionType("PAYMENT")
                .setAmountValue(new BigDecimal("10.00"))
                .setAmountCurrency("INR")
                .setStatus("SUCCESS")
                .setCanonicalStatus("COMPLETED")
                .setMerchantId("MER1")
                .setCustomerId("CUST1")
                .setSettlementId("SET1")
                .setDescription(null)
                .setProcessedAt(now)
                .build();
    }

    @Test
    void aConnectionAcquisitionFailureRetriesInsteadOfKillingTheWriterThread() throws Exception {
        DataSource flaky = new FlakyDataSource(realDataSource, 3);

        MockConsumer<String, CanonicalTransaction> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(PARTITION));
        Map<TopicPartition, Long> beginning = new HashMap<>();
        beginning.put(PARTITION, 0L);
        consumer.updateBeginningOffsets(beginning);
        consumer.addRecord(new ConsumerRecord<>(TOPIC, 0, 0L, "e1", sample("e1")));

        MockProducer<String, String> producer = new MockProducer<>(true, new StringSerializer(), new StringSerializer());

        WriterLoop writerLoop = new WriterLoop(consumer, flaky, new UpsertRepository(), new ConflictChecker(),
                new DlqPublisher(producer, "txn.canonical.dlq"), Duration.ofMillis(50));

        // processOnce() must not throw: it should retry past the 3 simulated connection failures and succeed.
        org.assertj.core.api.Assertions.assertThatCode(writerLoop::processOnce).doesNotThrowAnyException();

        try (Connection conn = realDataSource.getConnection();
                Statement stmt = conn.createStatement();
                var rs = stmt.executeQuery("SELECT count(*) FROM normalization.canonical_transactions")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }
}
