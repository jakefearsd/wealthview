package com.wealthview.app.it.user;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsers_asAdmin_returnsAll() {
        var response = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void updateRole_asAdmin_returns200() {
        var inviteCode = authHelper.createInviteCode();
        var memberToken = authHelper.registerAndGetToken(restTemplate,
                "member@test.com", "password123", inviteCode);

        var users = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        var memberUser = users.getBody().stream()
                .filter(u -> "member@test.com".equals(u.get("email")))
                .findFirst().orElseThrow();
        var memberId = (String) memberUser.get("id");

        var updateBody = Map.of("role", "admin");
        var response = restTemplate.exchange("/api/v1/tenant/users/" + memberId + "/role",
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("admin");
    }

    @Test
    void deleteUser_asAdmin_returns204() {
        var userId = authHelper.createUserDirectly("todelete@test.com", "password123", "member");

        var response = restTemplate.exchange("/api/v1/tenant/users/" + userId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void listUsers_asViewer_returns403() {
        var inviteCode = authHelper.createInviteCode();
        var memberToken = authHelper.registerAndGetToken(restTemplate,
                "viewer@test.com", "password123", inviteCode);

        // Members (non-admin) should be denied access to user listing
        // SecurityConfig requires ADMIN or SUPER_ADMIN role for GET /api/v1/tenant/users
        var response = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(memberToken),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
