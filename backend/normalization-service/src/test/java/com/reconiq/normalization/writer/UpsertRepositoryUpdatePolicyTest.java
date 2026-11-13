package com.reconiq.normalization.writer;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconiq.canonical.CanonicalTransaction;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ConflictPolicy.UPDATE was never actually exercised by a test before this (only the default REPORT
 * policy was) -- found while trying to verify it against the real sandbox, whose topic history had
 * accumulated so many replayed generations of the same events from a long debugging session that the
 * live result was inconclusive. A clean, isolated test is the reliable way to prove this.
 */
@Testcontainers
class UpsertRepositoryUpdatePolicyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private HikariDataSource dataSource;
    private final UpsertRepository updateRepository = new UpsertRepository(ConflictPolicy.UPDATE);

    @BeforeEach
    void setUp() throws Exception {
        dataSource = DataSourceFactory.create(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DataSourceFactory.migrate(dataSource);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE normalization.canonical_transactions");
        }
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private CanonicalTransaction sample(String eventId, String canonicalStatus, BigDecimal amount, Instant processedAt) {
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
                .setProcessedAt(processedAt)
                .build();
    }

    @Test
    void secondUpsertWithUpdatePolicyOverwritesEveryColumnExceptProcessedAt() throws Exception {
        Instant firstProcessedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondProcessedAt = Instant.parse("2026-06-01T00:00:00Z");

        CanonicalTransaction original = sample("e-update-1", "COMPLETED", new BigDecimal("999.00"), firstProcessedAt);
        CanonicalTransaction changed = sample("e-update-1", "FAILED", new BigDecimal("55555.00"), secondProcessedAt);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            updateRepository.upsertBatch(conn, List.of(original));
            conn.commit();

            List<UpsertResult> secondResult = updateRepository.upsertBatch(conn, List.of(changed));
            conn.commit();

            // DO UPDATE affects a row too, so it also reports as a successful write, not "skipped".
            assertThat(secondResult).containsExactly(UpsertResult.INSERTED);
        }

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(
                        "SELECT canonical_status, amount_value, processed_at FROM normalization.canonical_transactions "
                                + "WHERE event_id = 'e-update-1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("canonical_status")).isEqualTo("FAILED");
            assertThat(rs.getBigDecimal("amount_value")).isEqualByComparingTo("55555.00");
            // processed_at is deliberately excluded from DO UPDATE SET: the first value is always kept.
            assertThat(rs.getTimestamp("processed_at").toInstant()).isEqualTo(firstProcessedAt);
        }
    }
}
