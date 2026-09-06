package com.reconiq.reconciliation.audit;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostMapping("/system-events")
    public ResponseEntity<AuditRecord> recordSystemEvent(@RequestBody SystemEventRequest request) {
        AuditRecord saved = auditService.recordSystemEvent(
                request.action,
                request.source,
                request.details);
        return ResponseEntity.ok(saved);
    }

    public static class SystemEventRequest {
        public String action;
        public String source;
        public String details;
    }
}
