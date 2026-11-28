package com.reconiq.reporting.common;

/** Contract 1.3: why a value can't be computed. Add a code here and in the contract together. */
public enum UnavailableReason {
    PHASE3_RESULTS_NOT_STORED,
    PHASE5_NOT_PERSISTED,
    PHASE5_LIST_MISSING,
    PHASE6_NOT_BUILT,
    NO_DATA_YET,
    SERVICE_DOWN,
    RAW_EVENT_NOT_FOUND
}
