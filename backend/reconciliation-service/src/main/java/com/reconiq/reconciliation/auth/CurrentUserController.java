package com.reconiq.reconciliation.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class CurrentUserController {

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> currentUser(@AuthenticationPrincipal Object principal) {
        if (principal instanceof Jwt jwt) {
            return ResponseEntity.ok(identity(
                    "jwt",
                    jwt.getSubject(),
                    jwt.getIssuer() != null ? jwt.getIssuer().toString() : null));
        }
        if (principal instanceof OidcUser oidcUser) {
            return ResponseEntity.ok(identity(
                    "oidc",
                    oidcUser.getSubject(),
                    oidcUser.getIssuer() != null ? oidcUser.getIssuer().toString() : null));
        }
        return ResponseEntity.status(401).build();
    }

    private static Map<String, Object> identity(String authType, String subject, String issuer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authType", authType);
        body.put("subject", subject);
        body.put("issuer", issuer);
        return body;
    }
}
