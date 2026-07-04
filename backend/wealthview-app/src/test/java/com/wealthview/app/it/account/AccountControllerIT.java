package com.wealthview.app.it.account;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerIT extends AbstractApiIntegrationTest {

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
        data.createAccount("Account 1", "brokerage");
        data.createAccount("Account 2", "ira");

        var response = restTemplate.exchange("/api/v1/accounts",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var content = (java.util.List<Map<String, Object>>) response.getBody().get("data");
        assertThat(content).hasSize(2);
    }

    @Test
    void get_existingAccount_returns200() {
        var accountId = (String) data.createAccount("My IRA", "ira").get("id");

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
        var accountId = (String) data.createAccount("Old Name", "brokerage").get("id");
        var updateBody = Map.of("name", "New Name", "type", "brokerage", "institution", "Schwab");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("New Name");
        assertThat(response.getBody().get("institution")).isEqualTo("Schwab");
    }

    @Test
    void delete_existingAccount_returns204() {
        var accountId = (String) data.createAccount("To Delete", "brokerage").get("id");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var getResponse = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
