package com.reconiq.reconciliation.audit;

import com.reconiq.reconciliation.auth.CurrentUserController;
import com.reconiq.reconciliation.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CurrentUserController.class)
@Import({SecurityConfig.class, UserActionAuditListener.class, UserActionAuditListenerTest.OAuthTestConfig.class})
class UserActionAuditListenerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;

    @Test
    void authenticatedApiCallIsRecordedAsUserAction() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .with(jwt().jwt(token -> token.subject("user-1"))))
                .andExpect(status().isOk());

        verify(auditService).recordUserAction("user-1", "GET /api/auth/me");
    }

    @Test
    void unauthenticatedApiCallIsNotRecorded() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(auditService);
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
