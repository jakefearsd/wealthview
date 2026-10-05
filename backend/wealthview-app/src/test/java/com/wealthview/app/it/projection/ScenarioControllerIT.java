package com.wealthview.app.it.projection;

import java.util.List;
import java.util.Map;

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
}
