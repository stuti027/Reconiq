package com.reconiq.normalization.writer;

import com.reconiq.canonical.CanonicalTransaction;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ConflictChecker {

    private static final String SELECT_SQL = """
            SELECT event_id, transaction_id, source, event_timestamp, reference_id, reference_key,
                   transaction_type, amount_value, amount_currency, status, canonical_status,
                   merchant_id, customer_id, settlement_id, description
            FROM normalization.canonical_transactions
            WHERE event_id = ANY (?)
            """;

    /** True if the stored row for this event_id has the same content (all columns except processed_at). */
    public Map<String, Boolean> sameContent(Connection conn, List<CanonicalTransaction> candidates) throws SQLException {
        Map<String, CanonicalTransaction> byId = new HashMap<>();
        for (CanonicalTransaction t : candidates) {
            byId.put(t.getEventId(), t);
        }
        String[] ids = byId.keySet().toArray(new String[0]);

        Map<String, Boolean> results = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setArray(1, conn.createArrayOf("text", ids));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String eventId = rs.getString("event_id");
                    results.put(eventId, matches(byId.get(eventId), rs));
                }
            }
        }
        return results;
    }

    private boolean matches(CanonicalTransaction t, ResultSet rs) throws SQLException {
        BigDecimal storedAmount = rs.getBigDecimal("amount_value");
        boolean amountEqual = (t.getAmountValue() == null && storedAmount == null)
                || (t.getAmountValue() != null && storedAmount != null && t.getAmountValue().compareTo(storedAmount) == 0);

        return amountEqual
                && Objects.equals(t.getTransactionId(), rs.getString("transaction_id"))
                && Objects.equals(t.getSource(), rs.getString("source"))
                && t.getEventTimestamp().equals(toInstant(rs.getTimestamp("event_timestamp")))
                && Objects.equals(t.getReferenceId(), rs.getString("reference_id"))
                && Objects.equals(t.getReferenceKey(), rs.getString("reference_key"))
                && Objects.equals(t.getTransactionType(), rs.getString("transaction_type"))
                && Objects.equals(t.getAmountCurrency(), rs.getString("amount_currency"))
                && Objects.equals(t.getStatus(), rs.getString("status"))
                && Objects.equals(t.getCanonicalStatus(), rs.getString("canonical_status"))
                && Objects.equals(t.getMerchantId(), rs.getString("merchant_id"))
                && Objects.equals(t.getCustomerId(), rs.getString("customer_id"))
                && Objects.equals(t.getSettlementId(), rs.getString("settlement_id"))
                && Objects.equals(t.getDescription(), rs.getString("description"));
    }

    private Instant toInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}