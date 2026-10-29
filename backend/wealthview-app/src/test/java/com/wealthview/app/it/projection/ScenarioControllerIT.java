package com.wealthview.app.it.projection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_validScenario_returns201() {
        var body = data.scenarioBody("Basic Plan");

        var response = api.postForEntity("/api/v1/projections", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Basic Plan");
    }

    @Test
    void list_returnsCreatedScenarios() {
        data.createScenario("Plan A");
        data.createScenario("Plan B");

        var response = api.getListForEntity("/api/v1/projections");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void get_existingScenario_returns200() {
        var id = (String) data.createScenario("My Scenario").get("id");

        var response = api.getForEntity("/api/v1/projections/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My Scenario");
    }

    @Test
    void update_existingScenario_returns200() {
        var id = (String) data.createScenario("Old Name").get("id");
        var updateBody = data.scenarioBody("Updated Name");

        var response = api.putForEntity("/api/v1/projections/" + id, updateBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Updated Name");
    }

    @Test
    void delete_existingScenario_returns204() {
        var id = (String) data.createScenario("To Delete").get("id");

        var response = api.deleteForEntity("/api/v1/projections/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_existingScenario_returnsTaxSpaceAndTerminalValue() {
        var id = (String) data.createScenario("Runnable").get("id");

        var response = api.getForEntity("/api/v1/projections/" + id + "/run");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("yearly_data", "tax_space", "terminal_value");
        assertThat((List<?>) response.getBody().get("tax_space")).isNotEmpty();
        assertThat((Map<String, Object>) response.getBody().get("terminal_value"))
                .containsKeys("after_tax_legacy", "heir_tax_rate");
    }

    @Test
    void create_unknownSpendingProfileWithoutTent_returns404WithErrorEnvelope() {
        var body = new HashMap<>(data.scenarioBody("Ghost plan"));
        body.put("spending_profile_id", UUID.randomUUID().toString());

        var response = api.postForEntity("/api/v1/projections", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND").containsEntry("status", 404);
    }

    @Test
    void update_unknownSpendingProfileWithoutTent_returns404WithErrorEnvelope() {
        var id = (String) data.createScenario("Existing").get("id");
        var body = new HashMap<>(data.scenarioBody("Existing"));
        body.put("spending_profile_id", UUID.randomUUID().toString());

        var response = api.putForEntity("/api/v1/projections/" + id, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "NOT_FOUND").containsEntry("status", 404);
    }

    @Test
    void update_explicitNullBondTent_clearsTheStoredTent() {
        var profileId = data.createSpendingProfile("Core").get("id");
        var tent = Map.of("coverage_years", 3, "build_up_start_years", 0,
                "mixes", List.of(Map.of("name", "Plan", "shares",
                        Map.of("ultrashort", 1, "short", 0, "intermediate", 0, "long", 0))),
                "yields", Map.of("ultrashort", 0.03, "short", 0.035, "intermediate", 0.04, "long", 0.045));
        var withTent = new HashMap<>(data.scenarioBody("Tent"));
        withTent.put("spending_profile_id", profileId);
        withTent.put("bond_tent", tent);
        var created = api.postForEntity("/api/v1/projections", withTent);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) created.getBody().get("params_json")).contains("\"bond_tent\":{");
        var id = (String) created.getBody().get("id");
        var cleared = new HashMap<>(withTent);
        cleared.put("bond_tent", null);

        var response = api.putForEntity("/api/v1/projections/" + id, cleared);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) response.getBody().get("params_json")).doesNotContain("\"bond_tent\":{");
        assertThat((String) api.getForEntity("/api/v1/projections/" + id).getBody().get("params_json"))
                .doesNotContain("\"bond_tent\":{");
    }
}
