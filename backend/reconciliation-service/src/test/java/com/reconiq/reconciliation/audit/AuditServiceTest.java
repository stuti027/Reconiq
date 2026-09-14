package com.reconiq.reconciliation.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@Import(AuditService.class)
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Test
    void savesUserAction() {
        AuditRecord saved = auditService.recordUserAction("user-1", "GET /api/auth/me");

        assertEquals(1, auditRecordRepository.count());
        assertNotNull(saved.getId());
        assertEquals("USER", saved.getEventType());
        assertEquals("user-1", saved.getActor());
        assertEquals("GET /api/auth/me", saved.getAction());
        assertNull(saved.getSource());
        assertStoredHashMatchesRecord(saved);
    }

    @Test
    void savesSystemEvent() {
        AuditRecord saved = auditService.recordSystemEvent(
                "MATCH_FAILED",
                "PHASE_3",
                "transaction TXN00006");

        assertEquals(1, auditRecordRepository.count());
        assertEquals("SYSTEM", saved.getEventType());
        assertEquals("MATCH_FAILED", saved.getAction());
        assertEquals("PHASE_3", saved.getSource());
        assertEquals("transaction TXN00006", saved.getDetails());
        assertNull(saved.getActor());
        assertStoredHashMatchesRecord(saved);
    }

    private static void assertStoredHashMatchesRecord(AuditRecord saved) {
        assertNotNull(saved.getRecordHash());
        assertFalse(saved.getRecordHash().isEmpty());
        assertEquals(AuditRecordHasher.createHash(saved), saved.getRecordHash());
    }
}
