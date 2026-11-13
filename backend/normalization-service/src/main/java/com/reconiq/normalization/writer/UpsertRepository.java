package com.reconiq.normalization.writer;

import com.reconiq.canonical.CanonicalTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class UpsertRepository {

    private static final String BASE_INSERT = """
            INSERT INTO normalization.canonical_transactions
              (event_id, transaction_id, source, event_timestamp, reference_id, reference_key,
               transaction_type, amount_value, amount_currency, status, canonical_status,
               merchant_id, customer_id, settlement_id, description, processed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String DO_NOTHING = "ON CONFLICT (event_id) DO NOTHING";

    /** processed_at is deliberately excluded: the first processed_at is always kept, even in UPDATE mode. */
    private static final String DO_UPDATE = """
            ON CONFLICT (event_id) DO UPDATE SET
              transaction_id = EXCLUDED.transaction_id, source = EXCLUDED.source,
              event_timestamp = EXCLUDED.event_timestamp, reference_id = EXCLUDED.reference_id,
              reference_key = EXCLUDED.reference_key, transaction_type = EXCLUDED.transaction_type,
              amount_value = EXCLUDED.amount_value, amount_currency = EXCLUDED.amount_currency,
              status = EXCLUDED.status, canonical_status = EXCLUDED.canonical_status,
              merchant_id = EXCLUDED.merchant_id, customer_id = EXCLUDED.customer_id,
              settlement_id = EXCLUDED.settlement_id, description = EXCLUDED.description
            """;

    private final String upsertSql;

    public UpsertRepository() {
        this(ConflictPolicy.REPORT);
    }

    public UpsertRepository(ConflictPolicy policy) {
        this.upsertSql = BASE_INSERT + (policy == ConflictPolicy.UPDATE ? DO_UPDATE : DO_NOTHING);
    }

    /** Inserts every record in one JDBC batch on the given connection (caller controls the transaction). */
    public List<UpsertResult> upsertBatch(Connection conn, List<CanonicalTransaction> records) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            for (CanonicalTransaction t : records) {
                bind(stmt, t);
                stmt.addBatch();
            }
            int[] counts = stmt.executeBatch();
            List<UpsertResult> results = new ArrayList<>(counts.length);
            for (int count : counts) {
                results.add(count > 0 ? UpsertResult.INSERTED : UpsertResult.SKIPPED_DUPLICATE);
            }
            return results;
        }
    }

    /** Inserts a single record in its own statement; used for the row-by-row fallback after a batch data error. */
    public UpsertResult upsertOne(Connection conn, CanonicalTransaction t) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(upsertSql)) {
            bind(stmt, t);
            int count = stmt.executeUpdate();
            return count > 0 ? UpsertResult.INSERTED : UpsertResult.SKIPPED_DUPLICATE;
        }
    }

    private void bind(PreparedStatement stmt, CanonicalTransaction t) throws SQLException {
        stmt.setString(1, t.getEventId());
        stmt.setString(2, t.getTransactionId());
        stmt.setString(3, t.getSource());
        stmt.setTimestamp(4, Timestamp.from(t.getEventTimestamp()));
        stmt.setString(5, t.getReferenceId());
        stmt.setString(6, t.getReferenceKey());
        stmt.setString(7, t.getTransactionType());
        if (t.getAmountValue() != null) {
            stmt.setBigDecimal(8, t.getAmountValue());
        } else {
            stmt.setNull(8, Types.NUMERIC);
        }
        stmt.setString(9, t.getAmountCurrency());
        stmt.setString(10, t.getStatus());
        stmt.setString(11, t.getCanonicalStatus());
        stmt.setString(12, t.getMerchantId());
        stmt.setString(13, t.getCustomerId());
        stmt.setString(14, t.getSettlementId());
        stmt.setString(15, t.getDescription());
        stmt.setTimestamp(16, Timestamp.from(t.getProcessedAt()));
    }
}