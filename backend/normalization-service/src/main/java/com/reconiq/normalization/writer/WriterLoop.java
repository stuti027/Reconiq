package com.reconiq.normalization.writer;

import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.dlq.DlqRecord;
import com.reconiq.normalization.ops.Metrics;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Poll a batch from txn.canonical -> one DB transaction (upsert every row) -> COMMIT ->
 * DLQ records (must succeed) -> commit Kafka offsets. In that order, always.
 */
public class WriterLoop {

    private static final Logger log = LoggerFactory.getLogger(WriterLoop.class);
    private static final String APP_NAME = "normalization-service";
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000;

    private final Consumer<String, CanonicalTransaction> consumer;
    private final DataSource dataSource;
    private final UpsertRepository upsertRepository;
    private final ConflictChecker conflictChecker;
    private final DlqPublisher dlqPublisher;
    private final Duration pollTimeout;
    private final Metrics metrics;

    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>(Instant.now());
    private final AtomicReference<Instant> lastDbCommit = new AtomicReference<>();

    public WriterLoop(Consumer<String, CanonicalTransaction> consumer, DataSource dataSource,
            UpsertRepository upsertRepository, ConflictChecker conflictChecker, DlqPublisher dlqPublisher,
            Duration pollTimeout) {
        this(consumer, dataSource, upsertRepository, conflictChecker, dlqPublisher, pollTimeout, new Metrics());
    }

    public WriterLoop(Consumer<String, CanonicalTransaction> consumer, DataSource dataSource,
            UpsertRepository upsertRepository, ConflictChecker conflictChecker, DlqPublisher dlqPublisher,
            Duration pollTimeout, Metrics metrics) {
        this.consumer = consumer;
        this.dataSource = dataSource;
        this.upsertRepository = upsertRepository;
        this.conflictChecker = conflictChecker;
        this.dlqPublisher = dlqPublisher;
        this.pollTimeout = pollTimeout;
        this.metrics = metrics;
    }

    public Instant lastHeartbeat() {
        return lastHeartbeat.get();
    }

    public Instant lastDbCommit() {
        return lastDbCommit.get();
    }

    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                processOnce();
            }
        } catch (RuntimeException e) {
            // "Stop loudly": guarantee this is logged even if nothing else is watching. A silently
            // dying thread was a real bug found in the sandbox (see build-challenges-log.md).
            log.error("Writer loop stopping due to an unrecoverable error", e);
            throw e;
        }
    }

    /** One poll-process-commit cycle. Package-visible so tests can drive it deterministically. */
    void processOnce() {
        lastHeartbeat.set(Instant.now());
        ConsumerRecords<String, CanonicalTransaction> records = consumer.poll(pollTimeout);
        if (records.isEmpty()) {
            return;
        }

        List<ConsumerRecord<String, CanonicalTransaction>> batch = new ArrayList<>();
        records.forEach(batch::add);

        List<DlqRecord> dlqRecords = new ArrayList<>();
        processBatch(batch, dlqRecords);

        for (DlqRecord dlqRecord : dlqRecords) {
            dlqPublisher.publish(dlqRecord);
        }

        consumer.commitSync();
    }

    /**
     * Attempts the batch once with a fresh connection. If the database cannot even be reached --
     * including a HikariCP connection-acquisition failure or timeout, before any query is ever sent --
     * that is always an environment problem, never a data one, and falls through to the paused retry
     * loop. This is the fix for a real bug found in the sandbox: a connection-acquisition failure was
     * previously NOT classified at all and killed the writer thread silently (see build-challenges-log.md).
     */
    private void processBatch(List<ConsumerRecord<String, CanonicalTransaction>> batch, List<DlqRecord> dlqRecords) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            upsertBatchWithRetryAndFallback(conn, batch, dlqRecords);
        } catch (SQLException e) {
            log.warn("Could not use the database connection ({}), entering environment-outage retry", e.getMessage());
            retryWholeBatchUntilDatabaseRecovers(batch, dlqRecords);
        }
    }

    private void upsertBatchWithRetryAndFallback(Connection conn, List<ConsumerRecord<String, CanonicalTransaction>> batch,
            List<DlqRecord> dlqRecords) throws SQLException {
        List<CanonicalTransaction> transactions = batch.stream().map(ConsumerRecord::value).toList();
        try {
            List<UpsertResult> results = upsertRepository.upsertBatch(conn, transactions);
            conn.commit();
            onDbCommit();
            checkDuplicatesForConflicts(conn, batch, results, dlqRecords);
        } catch (SQLException e) {
            conn.rollback();
            ErrorClass errorClass = SqlErrorClassifier.classify(e);
            if (errorClass == ErrorClass.ENVIRONMENT) {
                retryWholeBatchUntilDatabaseRecovers(batch, dlqRecords);
            } else if (errorClass == ErrorClass.DATA) {
                upsertRowByRowIsolatingBadRows(conn, batch, dlqRecords);
            } else {
                throw new IllegalStateException("Unclassified SQL error (SQLState=" + e.getSQLState() + ")", e);
            }
        }
    }

    private void retryWholeBatchUntilDatabaseRecovers(List<ConsumerRecord<String, CanonicalTransaction>> batch,
            List<DlqRecord> dlqRecords) {
        var assignment = consumer.assignment();
        consumer.pause(assignment);
        long backoffMs = INITIAL_BACKOFF_MS;
        try {
            List<CanonicalTransaction> transactions = batch.stream().map(ConsumerRecord::value).toList();
            while (true) {
                sleep(backoffMs);
                consumer.poll(Duration.ZERO); // keep-alive only: partitions are paused, so this returns nothing
                metrics.incDbRetries();
                try (Connection conn = dataSource.getConnection()) {
                    conn.setAutoCommit(false);
                    List<UpsertResult> results = upsertRepository.upsertBatch(conn, transactions);
                    conn.commit();
                    onDbCommit();
                    checkDuplicatesForConflicts(conn, batch, results, dlqRecords);
                    return;
                } catch (SQLException e) {
                    if (SqlErrorClassifier.classify(e) != ErrorClass.ENVIRONMENT) {
                        throw new IllegalStateException("Unrecoverable error during environment-outage retry", e);
                    }
                    log.warn("Database still unavailable, retrying in {} ms", backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                }
            }
        } finally {
            consumer.resume(assignment);
        }
    }

    private void upsertRowByRowIsolatingBadRows(Connection conn, List<ConsumerRecord<String, CanonicalTransaction>> batch,
            List<DlqRecord> dlqRecords) throws SQLException {
        conn.setAutoCommit(true);
        List<ConsumerRecord<String, CanonicalTransaction>> succeeded = new ArrayList<>();
        List<UpsertResult> results = new ArrayList<>();
        for (ConsumerRecord<String, CanonicalTransaction> record : batch) {
            try {
                UpsertResult result = upsertRepository.upsertOne(conn, record.value());
                succeeded.add(record);
                results.add(result);
            } catch (SQLException e) {
                if (SqlErrorClassifier.classify(e) != ErrorClass.DATA) {
                    throw new IllegalStateException("Unrecoverable error during row-by-row fallback", e);
                }
                metrics.incDbRejected();
                dlqRecords.add(dlqRecord("REJECTED", "DB_REJECTED",
                        "SQLState=" + e.getSQLState() + " " + e.getMessage(), record));
            }
        }
        conn.setAutoCommit(false);
        checkDuplicatesForConflicts(conn, succeeded, results, dlqRecords);
        conn.commit();
        onDbCommit();
    }

    private void checkDuplicatesForConflicts(Connection conn, List<ConsumerRecord<String, CanonicalTransaction>> batch,
            List<UpsertResult> results, List<DlqRecord> dlqRecords) throws SQLException {
        Map<String, ConsumerRecord<String, CanonicalTransaction>> duplicateRecords = new HashMap<>();
        List<CanonicalTransaction> duplicateTransactions = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            if (results.get(i) == UpsertResult.SKIPPED_DUPLICATE) {
                CanonicalTransaction t = batch.get(i).value();
                duplicateRecords.put(t.getEventId(), batch.get(i));
                duplicateTransactions.add(t);
            }
        }
        if (duplicateTransactions.isEmpty()) {
            return;
        }
        Map<String, Boolean> sameContent = conflictChecker.sameContent(conn, duplicateTransactions);
        conn.commit();
        for (Map.Entry<String, Boolean> entry : sameContent.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                metrics.incDuplicatesSkipped();
            } else {
                metrics.incConflicts();
                ConsumerRecord<String, CanonicalTransaction> record = duplicateRecords.get(entry.getKey());
                dlqRecords.add(dlqRecord("REJECTED", "EVENT_ID_CONFLICT",
                        "event_id " + entry.getKey() + " already stored with different content", record));
            }
        }
    }

    private void onDbCommit() {
        lastDbCommit.set(Instant.now());
        metrics.incBatchesCommitted();
    }

    private DlqRecord dlqRecord(String kind, String reasonCode, String reasonDetail,
            ConsumerRecord<String, CanonicalTransaction> record) {
        return new DlqRecord(kind, reasonCode, reasonDetail, record.key(),
                String.valueOf(record.value()), record.topic(), record.partition(), record.offset(),
                record.timestamp(), Instant.now().toEpochMilli(), APP_NAME);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during backoff", e);
        }
    }
}
