package com.reconiq.reconciliation.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AuditRecordHasherTest {

    @Test
    void sameDataProducesTheSameHash() {
        Instant occurredAt = Instant.parse("2026-09-06T10:00:00Z");

        AuditRecord first = sampleRecord(occurredAt, "GET /api/auth/me");
        AuditRecord second = sampleRecord(occurredAt, "GET /api/auth/me");

        String firstHash = AuditRecordHasher.createHash(first);
        String secondHash = AuditRecordHasher.createHash(second);

        assertNotNull(firstHash);
        assertFalse(firstHash.isEmpty());
        assertEquals(64, firstHash.length());
        assertEquals(firstHash, secondHash);
    }

    @Test
    void differentDataProducesADifferentHash() {
        Instant occurredAt = Instant.parse("2026-09-06T10:00:00Z");

        AuditRecord original = sampleRecord(occurredAt, "GET /api/auth/me");
        AuditRecord changed = sampleRecord(occurredAt, "GET /api/admin/access");

        assertNotEquals(
                AuditRecordHasher.createHash(original),
                AuditRecordHasher.createHash(changed));
    }

    private static AuditRecord sampleRecord(Instant occurredAt, String action) {
        AuditRecord record = new AuditRecord();
        record.setOccurredAt(occurredAt);
        record.setEventType("USER");
        record.setActor("user-1");
        record.setAction(action);
        record.setSource(null);
        record.setDetails(null);
        return record;
    }
}
