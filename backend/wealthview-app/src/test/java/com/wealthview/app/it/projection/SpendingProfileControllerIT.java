package com.wealthview.app.it.projection;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class SpendingProfileControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_validProfile_returns201() {
        var body = data.spendingProfileBody("Comfortable");

        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("name")).isEqualTo("Comfortable");
    }

    @Test
    void list_returnsCreatedProfiles() {
        data.createSpendingProfile("Profile A");
        data.createSpendingProfile("Profile B");

        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void get_existingProfile_returns200() {
        var id = data.createSpendingProfileAndGetId("My Profile");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("My Profile");
    }

    @Test
    void update_existingProfile_returns200() {
        var id = data.createSpendingProfileAndGetId("Old Profile");
        var updateBody = data.spendingProfileBody("Updated Profile");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Updated Profile");
    }

    @Test
    void delete_existingProfile_returns204() {
        var id = data.createSpendingProfileAndGetId("To Delete");

        var response = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // === Spending Tiers Integration Tests ===

    @Test
    @SuppressWarnings("unchecked")
    void create_withSpendingTiers_persistsAndReturns() {
        var body = profileBodyWithTiers("Tiered Profile");

        var response = restTemplate.exchange("/api/v1/spending-profiles",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = (String) response.getBody().get("id");

        // GET back and verify tiers
        var getResponse = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var tiers = (List<Map<String, Object>>) getResponse.getBody().get("spending_tiers");
        assertThat(tiers).hasSize(2);

        var conservation = tiers.get(0);
        assertThat(conservation.get("name")).isEqualTo("Conservation");
        assertThat(((Number) conservation.get("start_age")).intValue()).isEqualTo(54);
        assertThat(((Number) conservation.get("end_age")).intValue()).isEqualTo(62);
        assertThat(((Number) conservation.get("essential_expenses")).doubleValue()).isEqualTo(96000.0);

        var goGo = tiers.get(1);
        assertThat(goGo.get("name")).isEqualTo("Go-Go");
        assertThat(goGo.get("end_age")).isNull();
        assertThat(((Number) goGo.get("essential_expenses")).doubleValue()).isEqualTo(156000.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void update_withSpendingTiers_replacesTiers() {
        // Create without tiers
        var id = data.createSpendingProfileAndGetId("No Tiers");

        // PUT with tiers
        var updateBody = profileBodyWithTiers("With Tiers");
        var updateResponse = restTemplate.exchange("/api/v1/spending-profiles/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        var tiers = (List<Map<String, Object>>) updateResponse.getBody().get("spending_tiers");
        assertThat(tiers).hasSize(2);
        assertThat(tiers.get(0).get("name")).isEqualTo("Conservation");
        assertThat(tiers.get(1).get("name")).isEqualTo("Go-Go");
    }

    private Map<String, Object> profileBodyWithTiers(String name) {
        // Map.of() doesn't allow null values, so use HashMap for Go-Go tier
        var conservationTier = Map.of(
                "name", "Conservation",
                "start_age", 54,
                "end_age", 62,
                "essential_expenses", 96000,
                "discretionary_expenses", 0
        );

        var goGoTier = new HashMap<String, Object>();
        goGoTier.put("name", "Go-Go");
        goGoTier.put("start_age", 62);
        goGoTier.put("end_age", null);
        goGoTier.put("essential_expenses", 156000);
        goGoTier.put("discretionary_expenses", 60000);

        return Map.of(
                "name", name,
                "essential_expenses", 40000,
                "discretionary_expenses", 20000,
                "income_streams", List.of(),
                "spending_tiers", List.of(conservationTier, goGoTier)
        );
    }
}
