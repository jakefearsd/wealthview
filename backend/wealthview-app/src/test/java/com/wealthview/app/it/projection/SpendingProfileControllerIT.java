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

class SpendingProfileControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
    }

    @Test
    void create_validProfile_returns201() {
        var body = profileBody("Comfortable");

        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Comfortable");
    }

    @Test
    void list_returnsCreatedProfiles() {
        createProfile("Profile A");
        createProfile("Profile B");

        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void get_existingProfile_returns200() {
        var id = createProfileAndGetId("My Profile");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My Profile");
    }

    @Test
    void update_existingProfile_returns200() {
        var id = createProfileAndGetId("Old Profile");
        var updateBody = profileBody("Updated Profile");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Updated Profile");
    }

    @Test
    void delete_existingProfile_returns204() {
        var id = createProfileAndGetId("To Delete");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private Map<String, Object> profileBody(String name) {
        return Map.of(
                "name", name,
                "essential_expenses", 40000,
                "discretionary_expenses", 20000,
                "income_streams", List.of(Map.of(
                        "name", "Social Security",
                        "annual_amount", 24000,
                        "start_age", 67
                ))
        );
    }

    private void createProfile(String name) {
        restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(profileBody(name), authHelper.adminToken()), MAP_TYPE);
    }

    private String createProfileAndGetId(String name) {
        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(profileBody(name), authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
