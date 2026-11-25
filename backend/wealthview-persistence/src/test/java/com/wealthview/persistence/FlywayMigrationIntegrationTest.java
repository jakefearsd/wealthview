package com.wealthview.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIntegrationTest extends AbstractIntegrationTest {

    // The PostgreSQL major the db service in docker-compose.yml and docker-compose.prod.yml
    // runs. The migrations are only proven against the server major these tests start, so
    // the Testcontainers image must move in lockstep with a production major upgrade.
    private static final int PRODUCTION_POSTGRES_MAJOR = 18;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDatabase_runsProductionPostgresMajorVersion() {
        var serverVersionNum = jdbcTemplate.queryForObject("SHOW server_version_num", String.class);

        assertThat(Integer.parseInt(serverVersionNum) / 10_000).isEqualTo(PRODUCTION_POSTGRES_MAJOR);
    }

    @Test
    void flywayMigrations_shouldApplySuccessfully() {
        var appliedMigrations = flyway.info().applied();

        assertThat(appliedMigrations).hasSizeGreaterThan(0);
        assertThat(appliedMigrations[0].getVersion().toString()).isEqualTo("001");
    }
}
