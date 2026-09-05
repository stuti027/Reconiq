package com.reconiq.reconciliation.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns role names from an OIDC/JWT token into Spring authorities.
 * Spring's hasRole("ADMIN") looks for the authority ROLE_ADMIN.
 */
public final class TokenRoles {

    public static final String ADMIN = "ADMIN";

    private TokenRoles() {
    }

    public static List<GrantedAuthority> fromRoleNames(List<String> roleNames) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roleNames == null) {
            return authorities;
        }
        for (String roleName : roleNames) {
            if (roleName == null || roleName.isEmpty()) {
                continue;
            }
            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
        }
        return authorities;
    }
}
