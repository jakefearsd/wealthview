package com.wealthview.app.it.account;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
    }

    @Test
    void create_validBrokerageAccount_returns201() {
        var body = Map.of("name", "Test Brokerage", "type", "brokerage", "institution", "Fidelity");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Test Brokerage");
        assertThat(response.getBody().get("type")).isEqualTo("brokerage");
        assertThat(response.getBody().get("institution")).isEqualTo("Fidelity");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_returnsCreatedAccounts() {
        createAccount("Account 1", "brokerage");
        createAccount("Account 2", "ira");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).hasSize(2);
    }

    @Test
    void get_existingAccount_returns200() {
        var accountId = createAccountAndGetId("My IRA", "ira");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My IRA");
    }

    @Test
    void get_nonExistent_returns404() {
        var response = restTemplate.exchange("/api/v1/accounts/" + UUID.randomUUID(),
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_existingAccount_returns200() {
        var accountId = createAccountAndGetId("Old Name", "brokerage");
        var updateBody = Map.of("name", "New Name", "type", "brokerage", "institution", "Schwab");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("New Name");
        assertThat(response.getBody().get("institution")).isEqualTo("Schwab");
    }

    @Test
    void delete_existingAccount_returns204() {
        var accountId = createAccountAndGetId("To Delete", "brokerage");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var getResponse = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void createAccount(String name, String type) {
        var body = Map.of("name", name, "type", type);
        restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
    }

    private String createAccountAndGetId(String name, String type) {
        var body = Map.of("name", name, "type", type);
        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
