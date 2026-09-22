package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class UpsertRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private HikariDataSource dataSource;
    private final UpsertRepository repository = new UpsertRepository();
    private final ConflictChecker conflictChecker = new ConflictChecker();

    @BeforeEach
    void setUp() {
        dataSource = DataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DataSourceFactory.migrate(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private CanonicalTransaction sample(String eventId, BigDecimal amount, String description) {
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
                .setCanonicalStatus("COMPLETED")
                .setMerchantId("MER1")
                .setCustomerId("CUST1")
                .setSettlementId("SET1")
                .setDescription(description)
                .setProcessedAt(now)
                .build();
    }

    @Test
    void insertsNewRows() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var results = repository.upsertBatch(conn, List.of(sample("e1", new BigDecimal("10.00"), null)));
            conn.commit();
            assertThat(results).containsExactly(UpsertResult.INSERTED);
        }
    }

    @Test
    void repeatedEventIdWithSameContentIsSkippedAsDuplicate() throws Exception {
        CanonicalTransaction t = sample("e2", new BigDecimal("10.00"), null);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            repository.upsertBatch(conn, List.of(t));
            conn.commit();
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var results = repository.upsertBatch(conn, List.of(t));
            conn.commit();
            assertThat(results).containsExactly(UpsertResult.SKIPPED_DUPLICATE);

            Map<String, Boolean> matches = conflictChecker.sameContent(conn, List.of(t));
            assertThat(matches.get("e2")).isTrue();
        }
    }

    @Test
    void repeatedEventIdWithDifferentContentIsDetectedAsConflict() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            repository.upsertBatch(conn, List.of(sample("e3", new BigDecimal("10.00"), null)));
            conn.commit();
        }
        CanonicalTransaction changed = sample("e3", new BigDecimal("99.00"), "different now");
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            var results = repository.upsertBatch(conn, List.of(changed));
            conn.commit();
            assertThat(results).containsExactly(UpsertResult.SKIPPED_DUPLICATE);

            Map<String, Boolean> matches = conflictChecker.sameContent(conn, List.of(changed));
            assertThat(matches.get("e3")).isFalse();
        }
    }

    @Test
    void nullAmountIsPreservedAndComparesEqual() throws Exception {
        CanonicalTransaction t = sample("e4", null, null);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            repository.upsertBatch(conn, List.of(t));
            conn.commit();
            repository.upsertBatch(conn, List.of(t));
            conn.commit();
            assertThat(conflictChecker.sameContent(conn, List.of(t)).get("e4")).isTrue();
        }
    }
}