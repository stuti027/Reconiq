package com.reconiq.normalization.topology;

import com.reconiq.canonical.CanonicalTransaction;
import com.reconiq.normalization.dlq.DlqRecord;

public sealed interface Outcome {

    record Canonical(CanonicalTransaction transaction) implements Outcome {
    }

    record Rejected(DlqRecord record) implements Outcome {
    }

    record Flagged(DlqRecord record) implements Outcome {
    }
}