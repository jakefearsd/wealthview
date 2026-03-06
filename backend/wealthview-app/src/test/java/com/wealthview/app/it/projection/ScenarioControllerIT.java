package com.wealthview.app.it.projection;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
    }

    @Test
    void create_validScenario_returns201() {
        var body = scenarioBody("Basic Plan");

        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Basic Plan");
    }

    @Test
    void list_returnsCreatedScenarios() {
        createScenario("Plan A");
        createScenario("Plan B");

        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void get_existingScenario_returns200() {
        var id = createScenarioAndGetId("My Scenario");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My Scenario");
    }

    @Test
    void update_existingScenario_returns200() {
        var id = createScenarioAndGetId("Old Name");
        var updateBody = scenarioBody("Updated Name");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Updated Name");
    }

    @Test
    void delete_existingScenario_returns204() {
        var id = createScenarioAndGetId("To Delete");

        var response = restTemplate.exchange("/api/v1/projections/" + id,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private Map<String, Object> scenarioBody(String name) {
        return Map.of(
                "name", name,
                "retirement_date", "2055-01-01",
                "end_age", 90,
                "inflation_rate", 0.03,
                "birth_year", 1990,
                "withdrawal_rate", 0.04,
                "withdrawal_strategy", "fixed",
                "accounts", List.of(Map.of(
                        "initial_balance", 100000,
                        "annual_contribution", 20000,
                        "expected_return", 0.07,
                        "account_type", "taxable"
                ))
        );
    }

    private void createScenario(String name) {
        restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(scenarioBody(name), authHelper.adminToken()), MAP_TYPE);
    }

    private String createScenarioAndGetId(String name) {
        var response = restTemplate.exchange("/api/v1/projections",
                HttpMethod.POST, authHelper.authEntity(scenarioBody(name), authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
