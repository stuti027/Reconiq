package com.reconiq.reconciliation.audit;

import com.reconiq.reconciliation.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class)
@Import({SecurityConfig.class, AuditControllerTest.OAuthTestConfig.class})
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    @Test
    void unauthenticatedSystemEventIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/audit/system-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MATCH_FAILED\",\"source\":\"PHASE_3\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedCallerCanRecordSystemEvent() throws Exception {
        AuditRecord saved = new AuditRecord();
        saved.setOccurredAt(Instant.parse("2026-09-06T10:00:00Z"));
        saved.setEventType("SYSTEM");
        saved.setAction("MATCH_FAILED");
        saved.setSource("PHASE_3");
        saved.setDetails("transaction TXN00006");
        when(auditService.recordSystemEvent("MATCH_FAILED", "PHASE_3", "transaction TXN00006"))
                .thenReturn(saved);

        mockMvc.perform(post("/api/audit/system-events")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MATCH_FAILED\",\"source\":\"PHASE_3\",\"details\":\"transaction TXN00006\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("SYSTEM"))
                .andExpect(jsonPath("$.action").value("MATCH_FAILED"))
                .andExpect(jsonPath("$.source").value("PHASE_3"));

        verify(auditService).recordSystemEvent("MATCH_FAILED", "PHASE_3", "transaction TXN00006");
    }

    @TestConfiguration
    static class OAuthTestConfig {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            ClientRegistration registration = ClientRegistration.withRegistrationId("reconiq")
                    .clientId("test-client")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("http://127.0.0.1:9/oauth/authorize")
                    .tokenUri("http://127.0.0.1:9/oauth/token")
                    .userInfoUri("http://127.0.0.1:9/userinfo")
                    .jwkSetUri("http://127.0.0.1:9/jwks")
                    .issuerUri("http://127.0.0.1:9")
                    .scope("openid")
                    .build();
            return new InMemoryClientRegistrationRepository(registration);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("JWT decoding is not used in these tests");
            };
        }
    }
}
