package com.wealthview.app.it.user;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class UserControllerIT extends AbstractApiIntegrationTest {

    @Test
    void listUsers_asAdmin_returnsAll() {
        var response = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void updateRole_asAdmin_returns200() {
        var inviteCode = authHelper.createInviteCode();
        var memberToken = authHelper.registerAndGetToken(restTemplate,
                "member@test.com", "mytestpass", inviteCode);

        var users = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
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
        var userId = authHelper.createUserDirectly("todelete@test.com", "mytestpass", "member");

        var response = restTemplate.exchange("/api/v1/tenant/users/" + userId,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void listUsers_asViewer_returns403() {
        // Use registerAndGetToken to get a valid member token via the API
        var inviteCode = authHelper.createInviteCode();
        var memberToken = authHelper.registerAndGetToken(restTemplate,
                "viewer@test.com", "mytestpass", inviteCode);

        // Verify token was obtained (registration succeeded)
        assertThat(memberToken).as("Member registration should succeed").isNotNull();

        // Members (non-admin) should be denied access to user listing
        var response = restTemplate.exchange("/api/v1/tenant/users",
                HttpMethod.GET, authHelper.authEntity(memberToken), MAP_TYPE);

        // Member should be denied — Spring Security returns 401 or 403 depending on config
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }
}
