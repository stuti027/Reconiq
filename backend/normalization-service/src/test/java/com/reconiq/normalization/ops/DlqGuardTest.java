package com.reconiq.normalization.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DlqGuardTest {

    @Test
    void tripsOnProcessingErrorBurst() {
        DlqGuard guard = new DlqGuard(Duration.ofSeconds(60), 5, 0.5, 100, DlqGuard.Action.ALARM, null);
        for (int i = 0; i < 4; i++) {
            guard.recordOutcome(true, "PROCESSING_ERROR");
        }
        assertThat(guard.isTripped()).isFalse();

        guard.recordOutcome(true, "PROCESSING_ERROR");
        assertThat(guard.isTripped()).isTrue();
    }

    @Test
    void tripsOnRejectRatioOverMinimumRecords() {
        DlqGuard guard = new DlqGuard(Duration.ofSeconds(60), 999, 0.5, 10, DlqGuard.Action.ALARM, null);
        for (int i = 0; i < 4; i++) {
            guard.recordOutcome(true, "INVALID_JSON");
        }
        for (int i = 0; i < 5; i++) {
            guard.recordOutcome(false, null);
        }
        assertThat(guard.isTripped()).isFalse(); // 9 records, below minRecordsForRatio=10

        guard.recordOutcome(true, "INVALID_JSON");
        assertThat(guard.isTripped()).isTrue(); // 10 records, 5/10 = 0.5 >= threshold
    }

    @Test
    void doesNotTripBelowThresholds() {
        DlqGuard guard = new DlqGuard(Duration.ofSeconds(60), 5, 0.5, 100, DlqGuard.Action.ALARM, null);
        for (int i = 0; i < 100; i++) {
            guard.recordOutcome(false, null);
        }
        assertThat(guard.isTripped()).isFalse();
    }

    @Test
    void callsStopActionOnlyWhenConfiguredAndOnlyOnceUntilTripStateChanges() {
        AtomicBoolean stopped = new AtomicBoolean(false);
        DlqGuard guard = new DlqGuard(Duration.ofSeconds(60), 2, 0.99, 999, DlqGuard.Action.STOP, () -> stopped.set(true));
        guard.recordOutcome(true, "PROCESSING_ERROR");
        assertThat(stopped.get()).isFalse();
        guard.recordOutcome(true, "PROCESSING_ERROR");
        assertThat(stopped.get()).isTrue();
    }
}
