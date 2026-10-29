package com.wealthview.app.it.projection;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.core.projection.ScenarioParamsValidator;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.ScenarioParams;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CashReserveConversionIT extends AbstractApiIntegrationTest {

    private static final String V085 = "db/migration/V085__convert_cash_reserves_to_bond_tents.sql";
    private static final String V086 = "db/migration/V086__mark_cash_reserve_guardrail_profiles_stale.sql";
    private static final String EXISTING_TENT = """
            {"birth_year":1990,"bond_tent":{"coverage_years":3,"build_up_start_years":2,"mixes":[{"name":"Mine",
            "shares":{"ultrashort":0,"short":1,"intermediate":0,"long":0}}],"yields":{"ultrashort":0.03,
            "short":0.03,"intermediate":0.03,"long":0.03}}}""";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper mapper = new ObjectMapper();

    private UUID scenarioWithProfile(String name, int reserveYears, boolean activePlan, String paramsJson) {
        var scenarioId = UUID.fromString((String) data.createScenario(name).get("id"));
        var tenantId = jdbc.queryForObject("SELECT tenant_id FROM projection_scenarios WHERE id = ?",
                UUID.class, scenarioId);
        var profileId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guardrail_spending_profiles
                    (id, tenant_id, scenario_id, name, essential_floor, scenario_hash, is_stale)
                VALUES (?, ?, ?, 'Optimized', 30000, 'hash', false)""", profileId, tenantId, scenarioId);
        jdbc.update("UPDATE guardrail_spending_profiles SET cash_reserve_years = ? WHERE id = ?",
                reserveYears, profileId);
        if (activePlan) {
            jdbc.update("UPDATE projection_scenarios SET guardrail_profile_id = ? WHERE id = ?",
                    profileId, scenarioId);
        }
        if (paramsJson != null) {
            jdbc.update("UPDATE projection_scenarios SET params_json = ?::jsonb WHERE id = ?",
                    paramsJson, scenarioId);
        }
        return scenarioId;
    }

    private void runConversion() throws SQLException {
        runScript(V085);
    }

    private void runScript(String resource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
        }
    }

    private String paramsJson(UUID scenarioId) {
        return jdbc.queryForObject("SELECT params_json::text FROM projection_scenarios WHERE id = ?",
                String.class, scenarioId);
    }

    private boolean isStale(UUID scenarioId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_stale FROM guardrail_spending_profiles WHERE scenario_id = ?", Boolean.class, scenarioId));
    }

    @Test
    void convert_reserveScenario_writesCompleteValidTent() throws SQLException {
        var id = scenarioWithProfile("Reserve", 2, true, null);

        runConversion();

        var tent = ScenarioParams.parseOrEmpty(mapper, paramsJson(id)).bondTent();
        assertThat(tent.coverageYears()).isEqualByComparingTo("2");
        assertThat(tent.buildUpStartYears()).isZero();
        assertThat(tent.mixes()).hasSize(1);
        assertThat(tent.planMix().name()).isEqualTo("Cash reserve");
        assertThat(tent.planMix().shares().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo("1");
        assertThat(tent.planMix().shares().sum()).isEqualByComparingTo("1");
        assertThat(tent.yields().get(BondMaturity.ULTRASHORT)).isEqualByComparingTo(new BigDecimal("0.0394"));
        assertThat(tent.yields().get(BondMaturity.SHORT)).isEqualByComparingTo(new BigDecimal("0.0465"));
        assertThat(tent.yields().get(BondMaturity.INTERMEDIATE)).isEqualByComparingTo(new BigDecimal("0.048"));
        assertThat(tent.yields().get(BondMaturity.LONG)).isEqualByComparingTo(new BigDecimal("0.0539"));
        assertThat(tent.convertedFromCashReserve()).isTrue();
        assertThatCode(() -> ScenarioParamsValidator.validateBondTent(tent, null)).doesNotThrowAnyException();
        assertThat(isStale(id)).isTrue();
    }

    @Test
    void convert_reserveAboveTen_capsCoverageAtTen() throws SQLException {
        var id = scenarioWithProfile("Big reserve", 12, true, null);

        runConversion();

        assertThat(ScenarioParams.parseOrEmpty(mapper, paramsJson(id)).bondTent().coverageYears())
                .isEqualByComparingTo("10");
    }

    @Test
    void convert_zeroReserve_leavesScenarioUntouched() throws SQLException {
        var id = scenarioWithProfile("No reserve", 0, true, null);
        var before = paramsJson(id);

        runConversion();

        assertThat(paramsJson(id)).isEqualTo(before);
        assertThat(isStale(id)).isFalse();
    }

    @Test
    void convert_existingTent_isNotOverwritten() throws SQLException {
        var id = scenarioWithProfile("Has tent", 2, true, EXISTING_TENT);
        var before = paramsJson(id);

        runConversion();

        assertThat(paramsJson(id)).isEqualTo(before);
        assertThat(isStale(id)).isFalse();
    }

    @Test
    void convert_noActivePlan_leavesScenarioUntouched() throws SQLException {
        var id = scenarioWithProfile("No plan", 2, false, null);
        var before = paramsJson(id);

        runConversion();

        assertThat(paramsJson(id)).isEqualTo(before);
        assertThat(isStale(id)).isFalse();
    }

    @Test
    void convert_spendingProfilePlan_converts() throws SQLException {
        var id = scenarioWithProfile("Tier plan", 3, false, null);
        var spendingProfileId = UUID.fromString((String) data.createSpendingProfile("Core").get("id"));
        jdbc.update("UPDATE projection_scenarios SET spending_profile_id = ? WHERE id = ?", spendingProfileId, id);

        runConversion();

        assertThat(ScenarioParams.parseOrEmpty(mapper, paramsJson(id)).bondTent().coverageYears())
                .isEqualByComparingTo("3");
    }

    @Test
    void convert_otherParams_arePreserved() throws SQLException {
        var id = scenarioWithProfile("Other params", 2, true,
                "{\"birth_year\":1985,\"include_depression_years\":true}");

        runConversion();

        var params = ScenarioParams.parseOrEmpty(mapper, paramsJson(id));
        assertThat(params.birthYear()).isEqualTo(1985);
        assertThat(params.includeDepressionYears()).isTrue();
        assertThat(params.bondTent()).isNotNull();
    }

    @Test
    void convert_runTwice_changesNothingTheSecondTime() throws SQLException {
        var id = scenarioWithProfile("Twice", 2, true, null);
        runConversion();
        var afterFirst = paramsJson(id);
        jdbc.update("UPDATE guardrail_spending_profiles SET is_stale = false WHERE scenario_id = ?", id);

        runConversion();

        assertThat(paramsJson(id)).isEqualTo(afterFirst);
        assertThat(isStale(id)).isFalse();
    }

    @Test
    void markStale_reserveProfileWithoutActivePlan_becomesStale() throws SQLException {
        var id = scenarioWithProfile("Skipped by V085", 2, false, null);

        runScript(V086);

        assertThat(isStale(id)).isTrue();
    }

    @Test
    void markStale_noReserveProfile_staysFresh() throws SQLException {
        var id = scenarioWithProfile("No reserve", 0, false, null);

        runScript(V086);

        assertThat(isStale(id)).isFalse();
    }

    @Test
    void markStale_alreadyStaleProfile_isLeftUntouched() throws SQLException {
        var id = scenarioWithProfile("Already stale", 2, false, null);
        jdbc.update("UPDATE guardrail_spending_profiles SET is_stale = true WHERE scenario_id = ?", id);
        var updatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM guardrail_spending_profiles WHERE scenario_id = ?",
                Timestamp.class, id);

        runScript(V086);

        assertThat(jdbc.queryForObject(
                "SELECT updated_at FROM guardrail_spending_profiles WHERE scenario_id = ?",
                Timestamp.class, id)).isEqualTo(updatedAt);
    }

    @Test
    void markStale_doesNotTouchScenarioParams() throws SQLException {
        var id = scenarioWithProfile("Params stay", 2, false, null);
        var before = paramsJson(id);

        runScript(V086);

        assertThat(paramsJson(id)).isEqualTo(before);
    }

    @Test
    @SuppressWarnings("unchecked")
    void optimize_convertedScenario_succeedsAndReturnsBondTentTarget() throws SQLException {
        var id = scenarioWithProfile("Converted", 2, true, null);
        runConversion();
        var body = new HashMap<String, Object>();
        body.put("name", "Re-certified");
        body.put("trial_count", 100);
        body.put("confidence_level", 0.90);
        body.put("risk_tolerance", "moderate");

        var response = api.postForEntity("/api/v1/projections/" + id + "/optimize", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("bond_tent_target")).isNotNull();
        assertThat(((Number) response.getBody().get("bond_tent_target")).doubleValue()).isPositive();
    }

    @Test
    void insertWithoutCashReserveYears_newGuardrailProfile_defaultsToZero() {
        var secondId = UUID.fromString((String) data.createScenario("Fresh").get("id"));
        var tenantId = jdbc.queryForObject("SELECT tenant_id FROM projection_scenarios WHERE id = ?",
                UUID.class, secondId);
        jdbc.update("""
                INSERT INTO guardrail_spending_profiles (tenant_id, scenario_id, name, essential_floor, scenario_hash)
                VALUES (?, ?, 'Fresh', 1, 'h')""", tenantId, secondId);

        var years = jdbc.queryForObject(
                "SELECT cash_reserve_years FROM guardrail_spending_profiles WHERE scenario_id = ?",
                Integer.class, secondId);

        assertThat(years).isZero();
    }
}
