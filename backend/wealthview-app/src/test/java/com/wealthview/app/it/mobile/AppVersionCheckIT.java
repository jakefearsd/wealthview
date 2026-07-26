package com.wealthview.app.it.mobile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the mobile force-update endpoint and its admin
 * management surface. The migration seeds {@code 0.0.1 / 0.0.1} for both
 * platforms; some tests bump those values, so {@link #resetSeeds()} restores
 * them after every test to keep cases independent.
 */
class AppVersionCheckIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_PASS = "superpass123";
    private static final String SUPER_ADMIN_EMAIL = "version-super@wealthview.test";
    private static final String NON_ADMIN_EMAIL = "version-member@wealthview.test";
    private static final String NON_ADMIN_PASS = "memberpass1";

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthHelper.Session superAdminSession;
    private String memberToken;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        // Each test gets a fresh super-admin (the database cleaner truncated
        // users/tenants), and the version-check cache is invalidated so prior
        // tests' admin updates don't leak via cached entries.
        cacheManager.getCache("mobileAppVersions").clear();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdminSession = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        authHelper.createUserDirectly(NON_ADMIN_EMAIL, NON_ADMIN_PASS, "member");
        memberToken = authHelper.loginAs(restTemplate, NON_ADMIN_EMAIL, NON_ADMIN_PASS);
    }

    @AfterEach
    void resetSeeds() {
        // Restore the V063 seed values so subsequent test classes that may
        // assume the defaults still see them.
        jdbcTemplate.update("""
                UPDATE mobile_app_versions
                   SET minimum_supported_version = '0.0.1',
                       latest_version = '0.0.1',
                       store_url = CASE platform
                           WHEN 'android' THEN 'https://play.google.com/store/apps/details?id=com.wealthview'
                           WHEN 'ios'     THEN 'https://apps.apple.com/app/wealthview/id000000000'
                       END,
                       message = NULL,
                       updated_at = now()
                """);
        cacheManager.getCache("mobileAppVersions").clear();
    }

    // --- Public version-check ---

    @Test
    void versionCheck_anonymousAccess_succeeds() {
        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("platform")).isEqualTo("android");
    }

    @Test
    void versionCheck_androidOldVersion_returnsUpdateRequired() {
        bumpAndroid("1.5.0", "2.0.0", null);

        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=1.0.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("update_required")).isEqualTo(true);
        assertThat(response.getBody().get("update_recommended")).isEqualTo(false);
    }

    @Test
    void versionCheck_androidStaleButSupported_returnsUpdateRecommended() {
        bumpAndroid("1.0.0", "2.0.0", null);

        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=1.5.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("update_required")).isEqualTo(false);
        assertThat(response.getBody().get("update_recommended")).isEqualTo(true);
    }

    @Test
    void versionCheck_androidLatestVersion_returnsBothFalse() {
        bumpAndroid("1.0.0", "2.0.0", null);

        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=2.0.0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("update_required")).isEqualTo(false);
        assertThat(response.getBody().get("update_recommended")).isEqualTo(false);
    }

    @Test
    void versionCheck_iosWorks() {
        var response = api.getAnonForEntity("/api/v1/app/version-check?platform=ios&version=0.0.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("platform")).isEqualTo("ios");
    }

    @Test
    void versionCheck_unknownPlatform_returns400() {
        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=windows&version=1.0.0", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void versionCheck_malformedVersion_returns400() {
        for (var bad : List.of("not-a-version", "1.2", "1.2.3.4")) {
            var response = api.getAnonForEntity(
                    "/api/v1/app/version-check?platform=android&version=" + bad, String.class);

            assertThat(response.getStatusCode())
                    .as("malformed version %s should be rejected", bad)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void versionCheck_missingPlatform_returns400() {
        var response = api.getAnonForEntity("/api/v1/app/version-check?version=1.0.0", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void versionCheck_missingVersion_returns400() {
        var response = api.getAnonForEntity("/api/v1/app/version-check?platform=android", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void versionCheck_acceptsPreReleaseVersion() {
        var response = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=1.0.0-beta.1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- Admin management ---

    @Test
    void adminUpdateVersion_asSuperAdmin_persists() {
        var update = api.putForEntityAs(superAdminSession.accessToken(),
                "/api/v1/admin/mobile-versions/android",
                Map.of(
                        "minimum_supported_version", "1.0.0",
                        "latest_version", "1.5.0",
                        "store_url", "https://play.google.com/store/apps/details?id=com.wealthview",
                        "message", "Required for new tax features"));

        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);

        var list = api.getListForEntityAs(superAdminSession.accessToken(), "/api/v1/admin/mobile-versions");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        var android = list.getBody().stream()
                .filter(r -> "android".equals(r.get("platform")))
                .findFirst()
                .orElseThrow();
        assertThat(android.get("minimum_supported_version")).isEqualTo("1.0.0");
        assertThat(android.get("latest_version")).isEqualTo("1.5.0");
        assertThat(android.get("message")).isEqualTo("Required for new tax features");
    }

    @Test
    void adminUpdateVersion_asNonAdmin_returns403() {
        var response = api.putForEntityAs(memberToken,
                "/api/v1/admin/mobile-versions/android", validUpdateBody(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminUpdateVersion_anonymous_isRejected() {
        // For state-changing requests with no CSRF cookie/header, Spring's
        // CSRF filter rejects with 403 BEFORE authentication runs. Either
        // 401 (no auth) or 403 (no CSRF) is an acceptable "anonymous can't
        // do this" outcome — we just need to confirm the request is blocked.
        var response = api.putAnonForEntity(
                "/api/v1/admin/mobile-versions/android", validUpdateBody(), String.class);

        assertThat(response.getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void adminUpdateVersion_invalidSemver_returns400() {
        var response = api.putForEntityAs(superAdminSession.accessToken(),
                "/api/v1/admin/mobile-versions/android",
                Map.of(
                        "minimum_supported_version", "garbage",
                        "latest_version", "1.5.0",
                        "store_url", "https://example.com"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void adminUpdateVersion_unknownPlatform_returns400() {
        var response = api.putForEntityAs(superAdminSession.accessToken(),
                "/api/v1/admin/mobile-versions/windows", validUpdateBody(), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Cache behaviour ---

    @Test
    void versionCheckCache_repeatedCallsHitCache() {
        // First call seeds the cache; second/third should serve from it.
        for (int i = 0; i < 3; i++) {
            var response = api.getAnonForEntity(
                    "/api/v1/app/version-check?platform=android&version=0.0.1");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        var nativeCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>)
                cacheManager.getCache("mobileAppVersions").getNativeCache();
        var stats = nativeCache.stats();
        assertThat(stats.hitCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void adminUpdate_evictsCache() {
        // Prime the cache with the seed value.
        var first = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=0.0.1");
        assertThat(first.getBody().get("minimum_supported_version")).isEqualTo("0.0.1");

        // Bump via admin endpoint.
        bumpAndroid("1.5.0", "1.5.0", null);

        // Re-query: should reflect the new floor (proves the cache was evicted).
        var after = api.getAnonForEntity(
                "/api/v1/app/version-check?platform=android&version=1.0.0");
        assertThat(after.getBody().get("minimum_supported_version")).isEqualTo("1.5.0");
        assertThat(after.getBody().get("update_required")).isEqualTo(true);
    }

    // --- Metrics ---

    @Test
    void versionCheck_emitsCounterWithOutcomeTag() {
        bumpAndroid("1.5.0", "2.0.0", null);

        double before = readCounter("android", "update_required");

        api.getAnonForEntity("/api/v1/app/version-check?platform=android&version=1.0.0");

        double after = readCounter("android", "update_required");
        assertThat(after).isGreaterThan(before);
    }

    // --- helpers ---

    private void bumpAndroid(String min, String latest, String message) {
        var body = new HashMap<String, Object>();
        body.put("minimum_supported_version", min);
        body.put("latest_version", latest);
        body.put("store_url", "https://play.google.com/store/apps/details?id=com.wealthview");
        body.put("message", message);
        var response = api.putForEntityAs(superAdminSession.accessToken(),
                "/api/v1/admin/mobile-versions/android", body);
        assertThat(response.getStatusCode())
                .as("admin update of android row should succeed")
                .isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> validUpdateBody() {
        return Map.of(
                "minimum_supported_version", "1.0.0",
                "latest_version", "1.5.0",
                "store_url", "https://play.google.com/store/apps/details?id=com.wealthview");
    }

    private double readCounter(String platform, String outcome) {
        var counter = meterRegistry.find("wealthview.app.version_check_total")
                .tag("platform", platform)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
