package com.reconiq.reconciliation.auth;

import com.reconiq.reconciliation.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {CurrentUserController.class, AdminController.class})
@Import({SecurityConfig.class, AuthorizationSubphaseTest.OAuthTestConfig.class})
@TestPropertySource(properties = {
        "OIDC_CLIENT_ID=test-client",
        "OIDC_CLIENT_SECRET=test-secret",
        "OIDC_ISSUER_URI=http://127.0.0.1:9"
})
class AuthorizationSubphaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedAdminRequestIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/access").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserWithoutAdminRoleIsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/access").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRoleCanAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/access")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Admin access granted"));
    }

    @Test
    void userWithoutAdminRoleCanStillReadOwnIdentity() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .with(jwt().jwt(token -> token.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("user-1"));
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
