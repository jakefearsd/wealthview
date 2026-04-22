package com.wealthview.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigValidatorTest {

    private static MockEnvironment prodEnv() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static MockEnvironment dockerEnv() {
        var env = new MockEnvironment();
        env.setActiveProfiles("docker");
        return env;
    }

    @Test
    void validator_runsUnderProdProfile() {
        var profile = ProductionConfigValidator.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).contains("prod");
    }

    @Test
    void validator_runsUnderDockerProfile() {
        // docker-compose.yml sets SPRING_PROFILES_ACTIVE=docker — the validator
        // must enforce production secrets on that deployment path, not just under "prod".
        var profile = ProductionConfigValidator.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).contains("docker");
    }

    private static final String VALID_JWT_SECRET = "unique-production-secret-at-least-32-chars";
    private static final String VALID_SUPER_ADMIN_PASSWORD = "StrongPass123!";
    private static final String VALID_DB_PASSWORD = "super-secret-db-password";
    private static final List<String> VALID_CORS_ORIGINS = List.of("https://app.wealthview.example");

    @Test
    void validate_defaultJwtSecret_throws() {
        var validator = new ProductionConfigValidator(
                "default-secret-key-must-be-at-least-32-characters-long",
                VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validate_dockerComposeFallbackJwtSecret_throws() {
        // The docker-compose.yml file used to ship with a fallback equal to this string.
        // Make sure the validator rejects it even though it is not the historical default.
        var validator = new ProductionConfigValidator(
                "production-secret-key-must-be-at-least-32-characters",
                VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validate_jwtSecretShorterThan32Chars_throws() {
        var validator = new ProductionConfigValidator(
                "tooShort", VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validate_blankSuperAdminPassword_throws() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, "", VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_PASSWORD");
    }

    @Test
    void validate_demoSuperAdminPassword_throws() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, "admin123", VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_PASSWORD");
    }

    @Test
    void validate_blankDbPassword_throws() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, "",
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void validate_devFallbackDbPassword_throws() {
        // wv_dev_pass was the application.yml fallback; it must never be acceptable
        // in prod or docker, even if someone manually sets DB_PASSWORD=wv_dev_pass.
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, "wv_dev_pass",
                VALID_CORS_ORIGINS, prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void validate_validConfig_succeeds() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                VALID_CORS_ORIGINS, prodEnv());

        validator.validate(); // should not throw

        assertThat(true).isTrue(); // verify we reached here
    }

    @Test
    void prodProfile_emptyCorsOrigin_fails() {
        // CORS_ORIGIN must be explicitly configured in production — relying on
        // a silent empty default locks out the frontend or, worse, lets a future
        // Spring update change the implicit "allow-all" behavior unnoticed.
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of(), prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }

    @Test
    void prodProfile_blankCorsOrigin_fails() {
        // A single blank entry (e.g. from `${CORS_ORIGIN:}` expanding to empty
        // string and Spring splitting it into [""] ) must also fail.
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of(""), prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }

    @Test
    void prodProfile_nonHttpsOrigin_fails() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of("http://app.wealthview.example"), prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }

    @Test
    void prodProfile_mixedHttpsAndHttpOrigins_fails() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of("https://app.wealthview.example", "http://legacy.example"),
                prodEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }

    @Test
    void prodProfile_multipleHttpsOrigins_succeeds() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of("https://app.wealthview.example", "https://admin.wealthview.example"),
                prodEnv());

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void dockerProfile_localhostOrigin_succeeds() {
        // Docker-compose ships with http://localhost for local smoke testing;
        // the prod-only HTTPS rule must not apply under the docker profile.
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of("http://localhost"), dockerEnv());

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void dockerProfile_emptyCorsOrigin_fails() {
        var validator = new ProductionConfigValidator(
                VALID_JWT_SECRET, VALID_SUPER_ADMIN_PASSWORD, VALID_DB_PASSWORD,
                List.of(), dockerEnv());

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS_ORIGIN");
    }
}
