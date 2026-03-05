package com.wealthview.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void flywayMigrations_shouldApplySuccessfully() {
        var appliedMigrations = flyway.info().applied();

        assertThat(appliedMigrations).hasSizeGreaterThan(0);
        assertThat(appliedMigrations[0].getVersion().toString()).isEqualTo("1");
    }
}
