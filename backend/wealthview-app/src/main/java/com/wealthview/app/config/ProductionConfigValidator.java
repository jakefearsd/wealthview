package com.wealthview.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@Component
@Profile({"prod", "docker"})
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);
    private static final int MIN_JWT_SECRET_LENGTH = 32;
    private static final Set<String> KNOWN_DEFAULT_JWT_SECRETS = Set.of(
            "default-secret-key-must-be-at-least-32-characters-long",
            "production-secret-key-must-be-at-least-32-characters"
    );
    private static final Set<String> KNOWN_DEFAULT_SUPER_ADMIN_PASSWORDS = Set.of(
            "admin123",
            "demo123",
            "DevPass123!"
    );
    private static final Set<String> KNOWN_DEFAULT_DB_PASSWORDS = Set.of(
            "wv_dev_pass"
    );

    private final String jwtSecret;
    private final String superAdminPassword;
    private final String dbPassword;
    private final List<String> allowedCorsOrigins;
    private final Environment environment;

    public ProductionConfigValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.super-admin.password:}") String superAdminPassword,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${app.cors.allowed-origins:}") List<String> allowedCorsOrigins,
            Environment environment) {
        this.jwtSecret = jwtSecret;
        this.superAdminPassword = superAdminPassword;
        this.dbPassword = dbPassword;
        this.allowedCorsOrigins = allowedCorsOrigins;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        if (jwtSecret == null || jwtSecret.length() < MIN_JWT_SECRET_LENGTH
                || KNOWN_DEFAULT_JWT_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "SECURITY: JWT_SECRET must be set to a unique value of at least "
                            + MIN_JWT_SECRET_LENGTH + " characters in production. "
                            + "Do not use any of the development defaults.");
        }
        if (superAdminPassword == null || superAdminPassword.isBlank()
                || KNOWN_DEFAULT_SUPER_ADMIN_PASSWORDS.contains(superAdminPassword)) {
            throw new IllegalStateException(
                    "SECURITY: SUPER_ADMIN_PASSWORD must be set to a unique value in production. "
                            + "Do not use any of the development defaults.");
        }
        if (dbPassword == null || dbPassword.isBlank()
                || KNOWN_DEFAULT_DB_PASSWORDS.contains(dbPassword)) {
            throw new IllegalStateException(
                    "SECURITY: DB_PASSWORD must be set to a unique value in production. "
                            + "Do not use any of the development defaults.");
        }
        validateCorsOrigins();
        log.info("Production security configuration validated successfully");
    }

    private void validateCorsOrigins() {
        // Reject an empty or blank-only list so operators can't silently ship
        // a broken CORS policy by forgetting CORS_ORIGIN. Under the prod profile
        // every origin must additionally be https:// — the docker profile allows
        // http://localhost for the compose-based smoke deployment.
        if (allowedCorsOrigins == null
                || allowedCorsOrigins.isEmpty()
                || allowedCorsOrigins.stream().allMatch(s -> s == null || s.isBlank())) {
            throw new IllegalStateException(
                    "SECURITY: CORS_ORIGIN must be set to a non-empty list of allowed origins "
                            + "in production.");
        }

        var activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (activeProfiles.contains("prod")) {
            for (var origin : allowedCorsOrigins) {
                if (origin == null || !origin.startsWith("https://")) {
                    throw new IllegalStateException(
                            "SECURITY: CORS_ORIGIN entries must use the https:// scheme in "
                                    + "production, got: " + origin);
                }
            }
        }
    }
}
