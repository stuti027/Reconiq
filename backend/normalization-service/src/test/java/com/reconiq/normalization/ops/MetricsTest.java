package com.reconiq.normalization.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void countsAndSnapshotsCorrectly() {
        Metrics metrics = new Metrics();
        metrics.incRecordsIn("BANK");
        metrics.incRecordsIn("BANK");
        metrics.incRecordsIn("PAYMENT_GATEWAY");
        metrics.incCanonicalOut();
        metrics.incFlagged();
        metrics.incRejected("INVALID_JSON");
        metrics.incRejected("INVALID_JSON");
        metrics.incRejected("MISSING_EVENT_ID");
        metrics.incDuplicatesSkipped();
        metrics.incConflicts();
        metrics.incDbRejected();
        metrics.incDbRetries();
        metrics.incBatchesCommitted();

        var snapshot = metrics.snapshot();
        assertThat(snapshot.get("records_in")).isEqualTo(3L);
        assertThat(snapshot.get("canonical_out")).isEqualTo(1L);
        assertThat(snapshot.get("flagged")).isEqualTo(1L);
        assertThat(snapshot.get("rejected")).isEqualTo(3L);
        assertThat(metrics.totalRejected()).isEqualTo(3L);
        assertThat(snapshot.get("duplicates_skipped")).isEqualTo(1L);
        assertThat(snapshot.get("conflicts")).isEqualTo(1L);
        assertThat(snapshot.get("db_rejected")).isEqualTo(1L);
        assertThat(snapshot.get("db_retries")).isEqualTo(1L);
        assertThat(snapshot.get("batches_committed")).isEqualTo(1L);
    }
}
