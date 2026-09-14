package com.reconiq.reconciliation.audit;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {

    private final AuditRecordRepository auditRecordRepository;
    private final AuditRecordSigner auditRecordSigner;

    public AuditService(
            AuditRecordRepository auditRecordRepository,
            AuditRecordSigner auditRecordSigner) {
        this.auditRecordRepository = auditRecordRepository;
        this.auditRecordSigner = auditRecordSigner;
    }

    public AuditRecord recordUserAction(String actor, String action) {
        AuditRecord record = new AuditRecord();
        record.setOccurredAt(Instant.now());
        record.setEventType("USER");
        record.setActor(actor);
        record.setAction(action);
        protectRecord(record);
        return auditRecordRepository.save(record);
    }

    public AuditRecord recordSystemEvent(String action, String source, String details) {
        AuditRecord record = new AuditRecord();
        record.setOccurredAt(Instant.now());
        record.setEventType("SYSTEM");
        record.setAction(action);
        record.setSource(source);
        record.setDetails(details);
        protectRecord(record);
        return auditRecordRepository.save(record);
    }

    public boolean isRecordIntact(AuditRecord record) {
        String currentHash = AuditRecordHasher.createHash(record);
        if (!currentHash.equals(record.getRecordHash())) {
            return false;
        }
        return auditRecordSigner.verify(record.getRecordHash(), record.getRecordSignature());
    }

    private void protectRecord(AuditRecord record) {
        record.setRecordHash(AuditRecordHasher.createHash(record));
        record.setRecordSignature(auditRecordSigner.signHash(record.getRecordHash()));
    }
}
