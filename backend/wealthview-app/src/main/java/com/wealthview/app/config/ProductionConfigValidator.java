package com.wealthview.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile("prod")
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

    private final String jwtSecret;
    private final String superAdminPassword;

    public ProductionConfigValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.super-admin.password:}") String superAdminPassword) {
        this.jwtSecret = jwtSecret;
        this.superAdminPassword = superAdminPassword;
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
        log.info("Production security configuration validated successfully");
    }
}
