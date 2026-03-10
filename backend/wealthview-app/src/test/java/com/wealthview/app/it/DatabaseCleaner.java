package com.wealthview.app.it;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void clean() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    scenario_income_sources,
                    income_sources,
                    property_depreciation_schedule,
                    spending_profiles,
                    projection_accounts,
                    projection_scenarios,
                    property_valuations,
                    property_expenses,
                    property_income,
                    properties,
                    import_jobs,
                    holdings,
                    transactions,
                    accounts,
                    invite_codes,
                    users,
                    tenants
                CASCADE
                """);
    }
}
