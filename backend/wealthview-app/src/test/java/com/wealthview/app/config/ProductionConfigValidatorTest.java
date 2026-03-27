package com.wealthview.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigValidatorTest {

    @Test
    void validate_defaultJwtSecret_throws() {
        var validator = new ProductionConfigValidator(
                "default-secret-key-must-be-at-least-32-characters-long", "StrongPass123!");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void validate_blankSuperAdminPassword_throws() {
        var validator = new ProductionConfigValidator(
                "unique-production-secret-at-least-32-chars", "");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_PASSWORD");
    }

    @Test
    void validate_validConfig_succeeds() {
        var validator = new ProductionConfigValidator(
                "unique-production-secret-at-least-32-chars", "StrongPass123!");

        validator.validate(); // should not throw

        assertThat(true).isTrue(); // verify we reached here
    }
}
