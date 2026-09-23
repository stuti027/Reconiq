package com.reconiq.normalization.dlq;

public record DlqRecord(
        String kind,
        String reasonCode,
        String reasonDetail,
        String originalKey,
        String originalValue,
        String sourceTopic,
        int partition,
        long offset,
        long originalKafkaTimestamp,
        long failedAt,
        String app) {
}