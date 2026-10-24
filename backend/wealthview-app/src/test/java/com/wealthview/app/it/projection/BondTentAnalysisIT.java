package com.wealthview.app.it.projection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class BondTentAnalysisIT extends AbstractApiIntegrationTest {

    private static Map<String, Object> tent() {
        var shares = Map.of("ultrashort", 0.5, "short", 0.0, "intermediate", 0.5, "long", 0.0);
        var yields = Map.of("ultrashort", 0.03, "short", 0.035, "intermediate", 0.04, "long", 0.045);
        return Map.of("coverage_years", 5, "build_up_start_years", 3,
                "mixes", List.of(Map.of("name", "Plan", "shares", shares)), "yields", yields);
    }

    private String retireeWithTent() {
        var profileId = data.createSpendingProfile("Core").get("id");
        var body = new HashMap<>(data.scenarioBody("Tent analysis"));
        body.put("retirement_date", "2027-01-01");
        body.put("birth_year", 1962);
        body.put("end_age", 95);
        body.put("spending_profile_id", profileId);
        body.put("bond_tent", tent());
        body.put("accounts", List.of(Map.of(
                "initial_balance", 1500000,
                "annual_contribution", 0,
                "account_type", "taxable",
                "allocation", Map.of("us_stock", 100, "intl_stock", 0, "bond", 0, "cash", 0))));
        return (String) api.postForEntity("/api/v1/projections", body).getBody().get("id");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outcome(Map<String, Object> body, String name) {
        return ((List<Map<String, Object>>) body.get("outcomes")).stream()
                .filter(o -> name.equals(o.get("name"))).findFirst().orElseThrow();
    }

    private static double number(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).doubleValue();
    }

    @Test
    void replay_financialCrisis_noTentSellsStocksWhileDownAndTheTentDoesNot() {
        var id = retireeWithTent();

        var response = api.getForEntity(
                "/api/v1/projections/" + id + "/bond-tent/replay?crash=financial_crisis_2008&offset=0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body.get("crash_label")).isEqualTo("2007–09");
        assertThat(body.get("available_offsets")).isEqualTo(List.of(-1, 0, 1, 2, 3));
        var noTent = outcome(body, "No tent");
        var mix = outcome(body, "Plan");
        assertThat(number(noTent, "stocks_sold_while_down")).isPositive();
        assertThat(number(mix, "stocks_sold_while_down")).isLessThan(number(noTent, "stocks_sold_while_down"));
        assertThat(number(mix, "years_covered")).isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulate_scenarioWithTent_returnsNoTentAndEachMix() {
        var id = retireeWithTent();

        var response = api.getForEntity("/api/v1/projections/" + id + "/bond-tent/simulation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var columns = (List<Map<String, Object>>) response.getBody().get("columns");
        assertThat(columns).extracting(c -> c.get("name")).containsExactly("No tent", "Plan");
        assertThat(((Number) response.getBody().get("trial_count")).intValue()).isEqualTo(5000);
        assertThat(columns.getFirst()).containsKey("success_rate");
    }

    @Test
    void replay_otherTenant_returns404() {
        var id = retireeWithTent();
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.getForEntityAs(authHelper.tenant2Token(),
                "/api/v1/projections/" + id + "/bond-tent/replay?crash=dot_com_2000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
