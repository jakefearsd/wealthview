package com.wealthview.app.config;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"prod", "docker"})
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);
    private static final int MIN_JWT_SECRET_LENGTH = 32;
    // Prefix shared by every dev/IT sentinel fallback (see CLAUDE.md "Secrets & Pseudo-Secrets").
    // Catches any LOCAL_DEV_* value by shape, not just the specific literals enumerated below —
    // e.g. application-dev.yml's JWT fallback is long enough to pass the length check and isn't
    // in KNOWN_DEFAULT_JWT_SECRETS, so without this prefix check it would slip through.
    private static final String LOCAL_DEV_SENTINEL_PREFIX = "LOCAL_DEV_";
    // Profiles that activate dev/demo data seeding (SampleDataInitializer, DevDataInitializer).
    // These must never be simultaneously active alongside "prod" — Spring's @Profile matching
    // is OR-based, so a misconfigured SPRING_PROFILES_ACTIVE=prod,docker would otherwise seed a
    // demo@wealthview.local / demo123 admin account (a hardcoded password, not env-driven, so
    // none of the secret checks below would catch it).
    private static final Set<String> DEV_SEED_PROFILES = Set.of("dev", "docker");
    private static final Set<String> KNOWN_DEFAULT_JWT_SECRETS = Set.of(
            "default-secret-key-must-be-at-least-32-characters-long",
            "production-secret-key-must-be-at-least-32-characters"
    );
    private static final Set<String> KNOWN_DEFAULT_SUPER_ADMIN_PASSWORDS = Set.of(
            "admin123",
            "demo123",
            "LOCAL_DEV_NOT_A_REAL_PASSWORD_OVERRIDE_VIA_ENV"
    );
    private static final Set<String> KNOWN_DEFAULT_DB_PASSWORDS = Set.of(
            "LOCAL_DEV_NOT_A_REAL_PASSWORD_OVERRIDE_VIA_ENV"
    );
    private static final Set<String> KNOWN_DEFAULT_MFA_KEYS = Set.of(
            "REVWRUxPUE1FTlQtT05MWS1NRkEtS0VZLTMyLUJZVEU="
    );

    private final String jwtSecret;
    private final String superAdminPassword;
    private final String dbPassword;
    private final String mfaEncryptionKey;
    private final List<String> allowedCorsOrigins;
    private final Environment environment;

    public ProductionConfigValidator(
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.super-admin.password:}") String superAdminPassword,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${app.mfa.encryption-key:}") String mfaEncryptionKey,
            @Value("${app.cors.allowed-origins:}") List<String> allowedCorsOrigins,
            Environment environment) {
        this.jwtSecret = jwtSecret;
        this.superAdminPassword = superAdminPassword;
        this.dbPassword = dbPassword;
        this.mfaEncryptionKey = mfaEncryptionKey;
        this.allowedCorsOrigins = allowedCorsOrigins;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        validateNoDevSeedProfileWithProd();
        if (jwtSecret == null || jwtSecret.length() < MIN_JWT_SECRET_LENGTH
                || KNOWN_DEFAULT_JWT_SECRETS.contains(jwtSecret)
                || jwtSecret.startsWith(LOCAL_DEV_SENTINEL_PREFIX)) {
            throw new IllegalStateException(
                    "SECURITY: JWT_SECRET must be set to a unique value of at least "
                            + MIN_JWT_SECRET_LENGTH + " characters in production. "
                            + "Do not use any of the development defaults.");
        }
        if (superAdminPassword == null || superAdminPassword.isBlank()
                || KNOWN_DEFAULT_SUPER_ADMIN_PASSWORDS.contains(superAdminPassword)
                || superAdminPassword.startsWith(LOCAL_DEV_SENTINEL_PREFIX)) {
            throw new IllegalStateException(
                    "SECURITY: SUPER_ADMIN_PASSWORD must be set to a unique value in production. "
                            + "Do not use any of the development defaults.");
        }
        if (dbPassword == null || dbPassword.isBlank()
                || KNOWN_DEFAULT_DB_PASSWORDS.contains(dbPassword)
                || dbPassword.startsWith(LOCAL_DEV_SENTINEL_PREFIX)) {
            throw new IllegalStateException(
                    "SECURITY: DB_PASSWORD must be set to a unique value in production. "
                            + "Do not use any of the development defaults.");
        }
        if (mfaEncryptionKey == null || mfaEncryptionKey.isBlank()
                || KNOWN_DEFAULT_MFA_KEYS.contains(mfaEncryptionKey)
                || mfaEncryptionKey.startsWith(LOCAL_DEV_SENTINEL_PREFIX)) {
            throw new IllegalStateException(
                    "SECURITY: MFA_ENCRYPTION_KEY must be set to a unique base64-encoded "
                            + "32-byte value in production. Generate via `openssl rand -base64 32`.");
        }
        validateCorsOrigins();
        log.info("Production security configuration validated successfully");
    }

    /**
     * Defense-in-depth fail-fast: halts startup if "prod" is active alongside a dev/demo
     * seed profile. This is independent of {@code SampleDataInitializer}/{@code DevDataInitializer}'s
     * own {@code @Profile} annotations, which only prevent bean creation when the operator's
     * {@code SPRING_PROFILES_ACTIVE} is set correctly in the first place.
     */
    private void validateNoDevSeedProfileWithProd() {
        var activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (!activeProfiles.contains("prod")) {
            return;
        }
        var conflicting = activeProfiles.stream()
                .filter(DEV_SEED_PROFILES::contains)
                .collect(Collectors.toCollection(TreeSet::new));
        if (!conflicting.isEmpty()) {
            throw new IllegalStateException(
                    "SECURITY: the 'prod' profile must not be combined with dev/demo-seed "
                            + "profile(s) " + conflicting + ". SampleDataInitializer and "
                            + "DevDataInitializer seed accounts with known, non-env-driven "
                            + "passwords and must never run in production. Set "
                            + "SPRING_PROFILES_ACTIVE=prod only.");
        }
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
