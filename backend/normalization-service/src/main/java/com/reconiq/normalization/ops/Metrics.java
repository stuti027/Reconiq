package com.reconiq.normalization.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory counters exposed by /metrics. Thread-safe: written from Streams and writer threads, read from the HTTP thread. */
public class Metrics {

    private final AtomicLong recordsIn = new AtomicLong();
    private final AtomicLong canonicalOut = new AtomicLong();
    private final AtomicLong flagged = new AtomicLong();
    private final AtomicLong duplicatesSkipped = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong dbRejected = new AtomicLong();
    private final AtomicLong dbRetries = new AtomicLong();
    private final AtomicLong batchesCommitted = new AtomicLong();

    private final Map<String, AtomicLong> rejectedByReason = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> recordsInBySource = new ConcurrentHashMap<>();

    public void incRecordsIn(String source) {
        recordsIn.incrementAndGet();
        recordsInBySource.computeIfAbsent(orUnknown(source), k -> new AtomicLong()).incrementAndGet();
    }

    public void incCanonicalOut() {
        canonicalOut.incrementAndGet();
    }

    public void incFlagged() {
        flagged.incrementAndGet();
    }

    public void incRejected(String reasonCode) {
        rejectedByReason.computeIfAbsent(orUnknown(reasonCode), k -> new AtomicLong()).incrementAndGet();
    }

    public void incDuplicatesSkipped() {
        duplicatesSkipped.incrementAndGet();
    }

    public void incConflicts() {
        conflicts.incrementAndGet();
    }

    public void incDbRejected() {
        dbRejected.incrementAndGet();
    }

    public void incDbRetries() {
        dbRetries.incrementAndGet();
    }

    public void incBatchesCommitted() {
        batchesCommitted.incrementAndGet();
    }

    public long totalRejected() {
        return rejectedByReason.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** Ordered snapshot for JSON serialization. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records_in", recordsIn.get());
        m.put("canonical_out", canonicalOut.get());
        m.put("flagged", flagged.get());
        m.put("rejected", totalRejected());
        m.put("rejected_by_reason", copy(rejectedByReason));
        m.put("records_in_by_source", copy(recordsInBySource));
        m.put("duplicates_skipped", duplicatesSkipped.get());
        m.put("conflicts", conflicts.get());
        m.put("db_rejected", dbRejected.get());
        m.put("db_retries", dbRetries.get());
        m.put("batches_committed", batchesCommitted.get());
        return m;
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static Map<String, Long> copy(Map<String, AtomicLong> source) {
        Map<String, Long> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
}
