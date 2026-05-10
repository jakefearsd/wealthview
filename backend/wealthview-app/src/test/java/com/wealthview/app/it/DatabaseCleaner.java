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
                    stock_split_adjustments,
                    stock_splits,
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
                    exchange_rates,
                    accounts,
                    invite_codes,
                    refresh_tokens,
                    user_sessions,
                    mfa_recovery_codes,
                    mfa_challenges,
                    users,
                    tenants
                CASCADE
                """);
        // Reset the backfill flag so each split-related test starts fresh.
        jdbcTemplate.update("DELETE FROM system_config WHERE key LIKE 'stock_splits.%'");
    }
}
