package com.wealthview.app.it.projection;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code /api/v1/projections/{scenarioId}/optimize} and
 * {@code .../guardrail}, which had no integration test.
 *
 * <p>A guardrail profile IS a spending plan (see the spending-plan hierarchy in CLAUDE.md): it and
 * a tier-based spending profile are mutually exclusive implementations of one sealed interface,
 * and {@code GuardrailProfileService.optimize} is one of the two places that mutual exclusivity is
 * enforced. It is also the only endpoint that runs a Monte Carlo optimisation inside the request,
 * so its bounds ({@code trialCount} 100..50000, {@code confidenceLevel} in [0.5, 0.999]) are load
 * limits, not cosmetics.
 *
 * <p>Optimisations here use the minimum 100 trials — enough to exercise the whole wiring without
 * making the suite pay for statistical precision it does not assert on.
 */
class GuardrailControllerIT extends AbstractApiIntegrationTest {

    private static final int MIN_TRIALS = 100;

    private static String optimizeUrl(String scenarioId) {
        return "/api/v1/projections/" + scenarioId + "/optimize";
    }

    private static String guardrailUrl(String scenarioId) {
        return "/api/v1/projections/" + scenarioId + "/guardrail";
    }

    private static Map<String, Object> optimizeBody() {
        var body = new HashMap<String, Object>();
        body.put("name", "Base guardrail");
        body.put("trial_count", MIN_TRIALS);
        body.put("confidence_level", 0.90);
        body.put("risk_tolerance", "moderate");
        return body;
    }

    private String scenarioId() {
        return (String) data.createScenario("Guardrail scenario").get("id");
    }

    private String optimizedScenarioId() {
        var id = scenarioId();
        var response = api.postForEntity(optimizeUrl(id), optimizeBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    // === optimize ===

    @Test
    void optimize_validRequest_returns200WithAPersistedProfile() {
        var id = scenarioId();

        var response = api.postForEntity(optimizeUrl(id), optimizeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id")).isNotNull();
        assertThat(response.getBody().get("name")).isEqualTo("Base guardrail");
    }

    @Test
    void optimize_thenGetGuardrail_returnsTheSameProfile() {
        var id = optimizedScenarioId();

        var response = api.getForEntity(guardrailUrl(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Base guardrail");
    }

    @Test
    void optimize_trialCountBelowTheMinimum_returns400() {
        var id = scenarioId();
        var body = optimizeBody();
        body.put("trial_count", 99);

        assertThat(api.postForEntity(optimizeUrl(id), body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void optimize_trialCountAboveTheMaximum_returns400() {
        var id = scenarioId();
        var body = optimizeBody();
        body.put("trial_count", 50_001);

        assertThat(api.postForEntity(optimizeUrl(id), body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void optimize_confidenceLevelBelowAHalf_returns400() {
        var id = scenarioId();
        var body = optimizeBody();
        body.put("confidence_level", 0.49);

        assertThat(api.postForEntity(optimizeUrl(id), body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void optimize_confidenceLevelOfOne_returns400() {
        var id = scenarioId();
        var body = optimizeBody();
        body.put("confidence_level", 1.0);

        assertThat(api.postForEntity(optimizeUrl(id), body).getStatusCode())
                .as("a 100% success target is unachievable in a finite Monte Carlo sample")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void optimize_unknownScenario_returns404() {
        var response = api.postForEntity(optimizeUrl(UUID.randomUUID().toString()), optimizeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === get / delete / reoptimize ===

    @Test
    void getGuardrail_whenNoneHasBeenOptimised_returns404() {
        var response = api.getForEntity(guardrailUrl(scenarioId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteGuardrail_afterOptimising_returns204AndTheProfileIsGone() {
        var id = optimizedScenarioId();

        var deleteResponse = api.deleteForEntity(guardrailUrl(id));

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.getForEntity(guardrailUrl(id)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteGuardrail_whenNoneExists_returns404() {
        assertThat(api.deleteForEntity(guardrailUrl(scenarioId())).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void reoptimize_afterOptimising_returns200AndKeepsTheProfileName() {
        var id = optimizedScenarioId();

        var response = api.postForEntity(guardrailUrl(id) + "/reoptimize", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Base guardrail");
    }

    @Test
    void reoptimize_whenNoProfileExists_returns404() {
        var response = api.postForEntity(guardrailUrl(scenarioId()) + "/reoptimize", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === tenant isolation ===

    @Test
    void getGuardrail_forAnotherTenantsScenario_returns404() {
        var id = optimizedScenarioId();
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.getForEntityAs(authHelper.tenant2Token(), guardrailUrl(id));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void optimize_onAnotherTenantsScenario_returns404() {
        var id = scenarioId();
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.postForEntityAs(authHelper.tenant2Token(), optimizeUrl(id), optimizeBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteGuardrail_forAnotherTenantsScenario_returns404AndLeavesItIntact() {
        var id = optimizedScenarioId();
        authHelper.bootstrapSecondTenant(restTemplate);

        var deleteResponse = api.deleteForEntityAs(authHelper.tenant2Token(), guardrailUrl(id));

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.getForEntity(guardrailUrl(id)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
