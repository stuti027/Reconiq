package com.reconiq.reconciliation.audit;

import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.support.ServletRequestHandledEvent;

@Service
public class UserActionAuditListener {

    private final AuditService auditService;

    public UserActionAuditListener(AuditService auditService) {
        this.auditService = auditService;
    }

    @EventListener
    public void onRequestHandled(ServletRequestHandledEvent event) {
        String url = event.getRequestUrl();
        if (url == null || !url.contains("/api/")) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        if ("anonymousUser".equals(authentication.getName())) {
            return;
        }

        String action = event.getMethod() + " " + url;
        auditService.recordUserAction(authentication.getName(), action);
    }
}
