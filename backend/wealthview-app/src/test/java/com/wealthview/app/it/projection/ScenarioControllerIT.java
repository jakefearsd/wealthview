package com.wealthview.app.it.projection;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_validScenario_returns201() {
        var body = data.scenarioBody("Basic Plan");

        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Basic Plan");
    }

    @Test
    void list_returnsCreatedScenarios() {
        data.createScenario("Plan A");
        data.createScenario("Plan B");

        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void get_existingScenario_returns200() {
        var id = data.createScenarioAndGetId("My Scenario");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My Scenario");
    }

    @Test
    void update_existingScenario_returns200() {
        var id = data.createScenarioAndGetId("Old Name");
        var updateBody = data.scenarioBody("Updated Name");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Updated Name");
    }

    @Test
    void delete_existingScenario_returns204() {
        var id = data.createScenarioAndGetId("To Delete");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
