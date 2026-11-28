package com.reconiq.reporting.config;

import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.reconiq.reporting.common.BadRequestException;
import com.reconiq.reporting.common.CurrentUser;
import com.reconiq.reporting.common.ReportingUser;
import com.reconiq.reporting.common.Role;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Dev mode only (contract 1.7): the user comes from the header {@code X-Dev-User: name|ROLE},
 * sent by the frontend's role switcher. No header means a viewer called "dev".
 */
@Component
@ConditionalOnProperty(name = "reporting.auth-mode", havingValue = "dev", matchIfMissing = true)
public class DevHeaderCurrentUser implements CurrentUser {

    public static final String HEADER = "X-Dev-User";
    static final ReportingUser DEFAULT_USER = new ReportingUser("dev", Role.VIEWER);

    @Override
    public ReportingUser get() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return DEFAULT_USER;
        }
        return parse(attributes.getRequest());
    }

    static ReportingUser parse(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            return DEFAULT_USER;
        }
        String[] parts = header.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank()) {
            throw invalid();
        }
        try {
            Role role = Role.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
            return new ReportingUser(parts[0].trim(), role);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException(HEADER + " must be name|VIEWER, name|REVIEWER or name|ADMIN");
    }
}
