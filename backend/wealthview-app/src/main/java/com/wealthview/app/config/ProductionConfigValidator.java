package com.wealthview.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);
    private static final String DEFAULT_JWT_SECRET = "default-secret-key-must-be-at-least-32-characters-long";

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
        if (DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "SECURITY: JWT_SECRET must be set to a unique value in production. " +
                    "Do not use the default secret.");
        }
        if (superAdminPassword == null || superAdminPassword.isBlank()) {
            throw new IllegalStateException(
                    "SECURITY: SUPER_ADMIN_PASSWORD must be set in production.");
        }
        log.info("Production security configuration validated successfully");
    }
}
