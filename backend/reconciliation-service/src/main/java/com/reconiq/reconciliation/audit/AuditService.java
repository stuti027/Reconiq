package com.reconiq.reconciliation.audit;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {

    private final AuditRecordRepository auditRecordRepository;

    public AuditService(AuditRecordRepository auditRecordRepository) {
        this.auditRecordRepository = auditRecordRepository;
    }

    public AuditRecord recordUserAction(String actor, String action) {
        AuditRecord record = new AuditRecord();
        record.setOccurredAt(Instant.now());
        record.setEventType("USER");
        record.setActor(actor);
        record.setAction(action);
        record.setRecordHash(AuditRecordHasher.createHash(record));
        return auditRecordRepository.save(record);
    }

    public AuditRecord recordSystemEvent(String action, String source, String details) {
        AuditRecord record = new AuditRecord();
        record.setOccurredAt(Instant.now());
        record.setEventType("SYSTEM");
        record.setAction(action);
        record.setSource(source);
        record.setDetails(details);
        record.setRecordHash(AuditRecordHasher.createHash(record));
        return auditRecordRepository.save(record);
    }
}
