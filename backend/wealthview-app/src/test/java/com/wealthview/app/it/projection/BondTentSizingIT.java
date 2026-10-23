package com.wealthview.app.it.projection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class BondTentSizingIT extends AbstractApiIntegrationTest {

    private static Map<String, Object> tentBody() {
        var shares = Map.of("ultrashort", 0.25, "short", 0.25, "intermediate", 0.25, "long", 0.25);
        var yields = Map.of("ultrashort", 0.03, "short", 0.035, "intermediate", 0.04, "long", 0.045);
        return Map.of("coverage_years", 5, "build_up_start_years", 10,
                "mixes", List.of(Map.of("name", "Plan", "shares", shares)), "yields", yields);
    }

    private String scenarioWithSpendingProfile() {
        var profileId = data.createSpendingProfile("Core").get("id");
        var body = new HashMap<>(data.scenarioBody("Tent plan"));
        body.put("spending_profile_id", profileId);
        return (String) api.postForEntity("/api/v1/projections", body).getBody().get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sizeBondTent_scenarioWithSpendingProfile_returnsPositiveTargetPerMix() {
        var id = scenarioWithSpendingProfile();

        var response = api.postForEntity("/api/v1/projections/" + id + "/bond-tent/sizing", tentBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var mixes = (List<Map<String, Object>>) response.getBody().get("mixes");
        assertThat(mixes).hasSize(1);
        assertThat(((Number) mixes.getFirst().get("target")).doubleValue()).isPositive();
        assertThat(mixes.getFirst()).containsKey("percent_of_portfolio");
    }

    @Test
    void sizeBondTent_scenarioWithoutSpendingPlan_returns400() {
        var id = (String) data.createScenario("No plan").get("id");

        var response = api.postForEntity("/api/v1/projections/" + id + "/bond-tent/sizing", tentBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sizeBondTent_otherTenant_returns404() {
        var id = scenarioWithSpendingProfile();
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.postForEntityAs(authHelper.tenant2Token(),
                "/api/v1/projections/" + id + "/bond-tent/sizing", tentBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
