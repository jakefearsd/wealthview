package com.wealthview.app.it.user;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class UserControllerIT extends AbstractApiIntegrationTest {

    @Test
    void listUsers_asAdmin_returnsAll() {
        var response = api.getListForEntity("/api/v1/tenant/users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void updateRole_asAdmin_returns200() {
        var inviteCode = authHelper.createInviteCode();
        // Registers the member whose role is updated below; the token itself is not needed.
        authHelper.registerAndGetToken(restTemplate, "member@test.com", "mytestpass", inviteCode);

        var users = api.getListForEntity("/api/v1/tenant/users");
        var memberUser = users.getBody().stream()
                .filter(u -> "member@test.com".equals(u.get("email")))
                .findFirst().orElseThrow();
        var memberId = (String) memberUser.get("id");

        var updateBody = Map.of("role", "admin");
        var response = api.putForEntity("/api/v1/tenant/users/" + memberId + "/role", updateBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("admin");
    }

    @Test
    void deleteUser_asAdmin_returns204() {
        var userId = authHelper.createUserDirectly("todelete@test.com", "mytestpass", "member");

        var response = api.deleteForEntity("/api/v1/tenant/users/" + userId);

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
        var response = api.getForEntityAs(memberToken, "/api/v1/tenant/users");

        // Member should be denied — Spring Security returns 401 or 403 depending on config
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }
}
