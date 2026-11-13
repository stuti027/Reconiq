package com.reconiq.normalization.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trips when PROCESSING_ERROR bursts (N within a window) or the reject ratio over the last records is too high.
 * Every DLQ record keeps the original message, so tripping never loses data; it only marks /health DEGRADED
 * (or, if configured, stops the app).
 */
public class DlqGuard {

    private static final Logger log = LoggerFactory.getLogger(DlqGuard.class);

    public enum Action {
        ALARM, STOP
    }

    private final Duration burstWindow;
    private final int burstThreshold;
    private final double rejectRatioThreshold;
    private final int minRecordsForRatio;
    private final Action action;
    private final Runnable onStop;

    private final Deque<Instant> processingErrorTimestamps = new ArrayDeque<>();
    private final Deque<Boolean> recentOutcomes = new ArrayDeque<>(); // true = rejected
    private static final int RECENT_WINDOW = 200;

    private volatile boolean tripped = false;

    public DlqGuard(Duration burstWindow, int burstThreshold, double rejectRatioThreshold, int minRecordsForRatio,
            Action action, Runnable onStop) {
        this.burstWindow = burstWindow;
        this.burstThreshold = burstThreshold;
        this.rejectRatioThreshold = rejectRatioThreshold;
        this.minRecordsForRatio = minRecordsForRatio;
        this.action = action;
        this.onStop = onStop;
    }

    /** Call for every outcome. reasonCode is only meaningful when rejected=true. */
    public synchronized void recordOutcome(boolean rejected, String reasonCode) {
        recentOutcomes.addLast(rejected);
        while (recentOutcomes.size() > RECENT_WINDOW) {
            recentOutcomes.removeFirst();
        }

        if (rejected && "PROCESSING_ERROR".equals(reasonCode)) {
            Instant now = Instant.now();
            processingErrorTimestamps.addLast(now);
            pruneOlderThan(now.minus(burstWindow));
        }

        evaluate();
    }

    private void pruneOlderThan(Instant cutoff) {
        while (!processingErrorTimestamps.isEmpty() && processingErrorTimestamps.peekFirst().isBefore(cutoff)) {
            processingErrorTimestamps.removeFirst();
        }
    }

    private void evaluate() {
        boolean burstTripped = processingErrorTimestamps.size() >= burstThreshold;

        boolean ratioTripped = false;
        if (recentOutcomes.size() >= minRecordsForRatio) {
            long rejectedCount = recentOutcomes.stream().filter(Boolean::booleanValue).count();
            ratioTripped = (double) rejectedCount / recentOutcomes.size() >= rejectRatioThreshold;
        }

        boolean shouldTrip = burstTripped || ratioTripped;
        if (shouldTrip && !tripped) {
            tripped = true;
            log.error("DLQ guard tripped (burst={}, ratio={}). action={}", burstTripped, ratioTripped, action);
            if (action == Action.STOP && onStop != null) {
                onStop.run();
            }
        } else if (!shouldTrip && tripped) {
            tripped = false;
            log.info("DLQ guard cleared");
        }
    }

    public boolean isTripped() {
        return tripped;
    }
}
