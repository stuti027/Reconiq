package com.reconiq.reconciliation.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenRolesTest {

    @Test
    void convertsRoleNamesToSpringAuthorities() {
        List<GrantedAuthority> authorities = TokenRoles.fromRoleNames(List.of("ADMIN", "USER"));

        assertEquals(2, authorities.size());
        assertEquals("ROLE_ADMIN", authorities.get(0).getAuthority());
        assertEquals("ROLE_USER", authorities.get(1).getAuthority());
    }

    @Test
    void emptyWhenRolesAreMissing() {
        List<GrantedAuthority> authorities = TokenRoles.fromRoleNames(null);

        assertTrue(authorities.isEmpty());
    }
}
