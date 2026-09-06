package com.reconiq.reconciliation.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/admin/**").hasRole(TokenRoles.ADMIN)
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(this::mapOidcRoles)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new BearerTokenAuthenticationEntryPoint(),
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAccessDeniedHandlerFor(
                                new BearerTokenAccessDeniedHandler(),
                                new AntPathRequestMatcher("/api/**")));
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            return TokenRoles.fromRoleNames(roles);
        });
        return converter;
    }

    private Collection<GrantedAuthority> mapOidcRoles(Collection<? extends GrantedAuthority> authorities) {
        List<GrantedAuthority> mapped = new ArrayList<>();
        for (GrantedAuthority authority : authorities) {
            mapped.add(authority);
            if (authority instanceof OidcUserAuthority oidcUserAuthority) {
                List<String> roles = oidcUserAuthority.getIdToken().getClaimAsStringList("roles");
                mapped.addAll(TokenRoles.fromRoleNames(roles));
            }
        }
        return mapped;
    }
}
