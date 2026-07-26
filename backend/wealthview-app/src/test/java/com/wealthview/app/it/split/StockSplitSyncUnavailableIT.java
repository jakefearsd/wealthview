package com.wealthview.app.it.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the bean-absent path of {@code StockSplitController.sync()} that
 * {@link StockSplitSyncIT} cannot reach: that IT registers a stub
 * {@code SplitDetectionClient} via {@code @TestConfiguration}, which flips on
 * {@code FinnhubConfig}'s downstream {@code @ConditionalOnBean(SplitDetectionClient.class)}
 * guard on {@code StockSplitSyncService} and only proves the bean-present path.
 *
 * <p>This class deliberately registers NO such stub. {@code application-it.yml}
 * sets {@code app.finnhub.api-key: ""}, so {@code FinnhubConfig}'s
 * {@code @ConditionalOnExpression("!'${app.finnhub.api-key:}'.isEmpty()")} never
 * activates, no {@code SplitDetectionClient} bean is ever registered, and
 * {@code StockSplitSyncService} (itself {@code @ConditionalOnBean(SplitDetectionClient.class)})
 * is genuinely absent from the context. {@code StockSplitController}'s
 * {@code @Nullable StockSplitSyncService} field therefore resolves to null, and the
 * sync endpoint takes the {@code ServiceUnavailableException} branch, which
 * {@code GlobalExceptionHandler} maps to a 503 with the standard error envelope.
 */
class StockSplitSyncUnavailableIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "split-sync-unavailable-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";

    private AuthHelper.Session superAdmin;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
    }

    @Test
    void sync_whenSplitDetectionClientBeanAbsent_returns503WithStandardEnvelope() {
        var response = api.postForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/sync", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("error", "SERVICE_UNAVAILABLE");
        assertThat(response.getBody()).containsEntry("status", 503);
        assertThat((String) response.getBody().get("message")).isNotBlank();
    }
}
