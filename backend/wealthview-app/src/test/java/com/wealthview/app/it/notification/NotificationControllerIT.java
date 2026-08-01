package com.wealthview.app.it.notification;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code /api/v1/notifications/preferences}, which had no integration test.
 *
 * <p>Preferences are keyed by USER, not tenant — {@code NotificationController} passes
 * {@code principal.userId()} — and the GET is a synthesised view: the service returns one row per
 * known notification type, defaulting to enabled, overlaid with whatever the user has saved. So an
 * unsaved preference and an explicitly-enabled one are indistinguishable in the response, and only
 * a round-trip through both verbs shows the overlay actually works.
 */
class NotificationControllerIT extends AbstractApiIntegrationTest {

    private static final String URL = "/api/v1/notifications/preferences";
    private static final List<String> KNOWN_TYPES =
            List.of("LARGE_TRANSACTION", "IMPORT_COMPLETE", "IMPORT_FAILED");

    private static Map<String, Object> preference(String type, boolean enabled) {
        return Map.of("notification_type", type, "enabled", enabled);
    }

    @Test
    void getPreferences_withNothingSaved_returnsEveryKnownTypeEnabledByDefault() {
        var response = api.getListForEntity(URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(row -> row.get("notification_type"))
                .containsExactlyInAnyOrderElementsOf(KNOWN_TYPES);
        assertThat(response.getBody()).allSatisfy(row ->
                assertThat(row.get("enabled")).as("unset preferences default to on").isEqualTo(true));
    }

    @Test
    void updatePreferences_disablingOneType_isReflectedOnTheNextGet() {
        var body = Map.of("preferences", List.of(preference("IMPORT_FAILED", false)));

        var updateResponse = api.putForEntity(URL, body);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.getListForEntity(URL).getBody())
                .filteredOn(row -> "IMPORT_FAILED".equals(row.get("notification_type")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("enabled")).isEqualTo(false));
    }

    @Test
    void updatePreferences_leavesUnmentionedTypesAtTheirDefault() {
        api.putForEntity(URL, Map.of("preferences", List.of(preference("IMPORT_FAILED", false))));

        assertThat(api.getListForEntity(URL).getBody())
                .filteredOn(row -> !"IMPORT_FAILED".equals(row.get("notification_type")))
                .allSatisfy(row -> assertThat(row.get("enabled")).isEqualTo(true));
    }

    @Test
    void updatePreferences_appliedTwice_updatesTheExistingRowRatherThanDuplicatingIt() {
        api.putForEntity(URL, Map.of("preferences", List.of(preference("IMPORT_COMPLETE", false))));
        api.putForEntity(URL, Map.of("preferences", List.of(preference("IMPORT_COMPLETE", true))));

        var rows = api.getListForEntity(URL).getBody();

        assertThat(rows).hasSize(KNOWN_TYPES.size());
        assertThat(rows)
                .filteredOn(row -> "IMPORT_COMPLETE".equals(row.get("notification_type")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("enabled")).isEqualTo(true));
    }

    @Test
    void updatePreferences_missingPreferencesList_returns400() {
        var response = api.putForEntity(URL, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatePreferences_itemMissingEnabledFlag_returns400() {
        // Regression guard: the element constraints on PreferenceItem only run because the
        // request declares @Valid on the list. Without that cascade the null "enabled" reached
        // the service and auto-unboxed into the entity's primitive boolean, turning a malformed
        // request into a 500.
        var body = Map.of("preferences", List.of(Map.of("notification_type", "IMPORT_FAILED")));

        assertThat(api.putForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatePreferences_itemMissingNotificationType_returns400() {
        var body = Map.of("preferences", List.of(Map.of("enabled", true)));

        assertThat(api.putForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getPreferences_isScopedToTheCallingUser() {
        api.putForEntity(URL, Map.of("preferences", List.of(preference("IMPORT_FAILED", false))));
        authHelper.bootstrapSecondTenant(restTemplate);

        var otherUsersRows = api.getListForEntityAs(authHelper.tenant2Token(), URL).getBody();

        assertThat(otherUsersRows)
                .as("another user's saved preference must not alter this user's defaults")
                .allSatisfy(row -> assertThat(row.get("enabled")).isEqualTo(true));
    }

    @Test
    void anonymousRequest_isRejected() {
        var response = api.getAnonForEntity(URL);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
