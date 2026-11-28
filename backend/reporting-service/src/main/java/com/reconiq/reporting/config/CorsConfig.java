package com.reconiq.reporting.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The dashboard runs on another origin (Vite on http://localhost:5173), so the browser blocks its calls to
 * :8095 unless this service says that origin is allowed. Custom headers (X-Dev-User, Authorization) make the
 * browser send an OPTIONS "preflight" first; it must be allowed here too.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${reporting.cors.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders(DevHeaderCurrentUser.HEADER, "Authorization", "Content-Type")
                .maxAge(3600);
    }
}
