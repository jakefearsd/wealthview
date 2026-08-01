package com.wealthview.app.it.income;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level coverage for {@code /api/v1/income-sources}, which had no integration test.
 *
 * <p>Income sources feed the retirement projection engine directly (each becomes a per-year income
 * stream, with owner and survivor-percent driving the household first-death transition), so the
 * wire contract and — especially — the tenant scoping of this endpoint matter. The service
 * validates {@code income_type}, {@code tax_treatment}, {@code owner} and {@code survivor_percent}
 * against fixed sets in Java rather than at the schema level, so those rejections are only
 * observable through the API.
 */
class IncomeSourceControllerIT extends AbstractApiIntegrationTest {

    private static final String URL = "/api/v1/income-sources";

    private static Map<String, Object> pensionBody() {
        var body = new HashMap<String, Object>();
        body.put("name", "Acme Pension");
        body.put("income_type", "pension");
        body.put("annual_amount", 24000);
        body.put("start_age", 65);
        body.put("end_age", 90);
        body.put("inflation_rate", 0.02);
        body.put("tax_treatment", "taxable");
        return body;
    }

    private String createPensionAndGetId() {
        var response = api.postForEntity(URL, pensionBody());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    // === create ===

    @Test
    void create_validPension_returns201WithPersistedFields() {
        var response = api.postForEntity(URL, pensionBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var created = response.getBody();
        assertThat(created.get("id")).isNotNull();
        assertThat(created.get("name")).isEqualTo("Acme Pension");
        assertThat(created.get("income_type")).isEqualTo("pension");
        assertThat(((Number) created.get("annual_amount")).doubleValue()).isEqualTo(24000.0);
        assertThat(created.get("start_age")).isEqualTo(65);
        assertThat(created.get("end_age")).isEqualTo(90);
        assertThat(created.get("tax_treatment")).isEqualTo("taxable");
    }

    @Test
    void create_withoutOwner_defaultsToPrimaryWithFullSurvivorContinuation() {
        var response = api.postForEntity(URL, pensionBody());

        assertThat(response.getBody().get("owner")).isEqualTo("primary");
        assertThat(((Number) response.getBody().get("survivor_percent")).doubleValue()).isEqualTo(1.0);
    }

    @Test
    void create_ownedBySpouseWithPartialSurvivorPercent_persistsBoth() {
        var body = pensionBody();
        body.put("owner", "spouse");
        body.put("survivor_percent", 0.5);

        var response = api.postForEntity(URL, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("owner")).isEqualTo("spouse");
        assertThat(((Number) response.getBody().get("survivor_percent")).doubleValue()).isEqualTo(0.5);
    }

    @Test
    void create_unknownIncomeType_returns400() {
        var body = pensionBody();
        body.put("income_type", "lottery_winnings");

        var response = api.postForEntity(URL, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("error")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void create_unknownTaxTreatment_returns400() {
        var body = pensionBody();
        body.put("tax_treatment", "somehow_untaxed");

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_ownerJoint_returns400() {
        // "joint" is an account-only concept; an income source belongs to exactly one person.
        var body = pensionBody();
        body.put("owner", "joint");

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_blankName_returns400() {
        var body = pensionBody();
        body.put("name", "  ");

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_negativeAnnualAmount_returns400() {
        var body = pensionBody();
        body.put("annual_amount", -1);

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_survivorPercentAboveOne_returns400() {
        var body = pensionBody();
        body.put("survivor_percent", 1.5);

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_unknownPropertyId_returns404() {
        var body = pensionBody();
        body.put("income_type", "rental_property");
        body.put("property_id", UUID.randomUUID().toString());

        assertThat(api.postForEntity(URL, body).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === read ===

    @Test
    void list_afterCreatingTwo_returnsBoth() {
        api.postForEntity(URL, pensionBody());
        var second = pensionBody();
        second.put("name", "Social Security");
        second.put("income_type", "social_security");
        api.postForEntity(URL, second);

        var response = api.getListForEntity(URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2)
                .extracting(m -> m.get("name"))
                .containsExactlyInAnyOrder("Acme Pension", "Social Security");
    }

    @Test
    void get_existingId_returnsThatSource() {
        var id = createPensionAndGetId();

        var response = api.getForEntity(URL + "/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("id")).isEqualTo(id);
        assertThat(response.getBody().get("name")).isEqualTo("Acme Pension");
    }

    @Test
    void get_unknownId_returns404() {
        var response = api.getForEntity(URL + "/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === update / delete ===

    @Test
    void update_existingId_replacesMutableFields() {
        var id = createPensionAndGetId();
        var body = pensionBody();
        body.put("name", "Acme Pension (revised)");
        body.put("annual_amount", 30000);
        body.put("start_age", 67);

        var response = api.putForEntity(URL + "/" + id, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("Acme Pension (revised)");
        assertThat(((Number) response.getBody().get("annual_amount")).doubleValue()).isEqualTo(30000.0);
        assertThat(response.getBody().get("start_age")).isEqualTo(67);
    }

    @Test
    void update_unknownId_returns404() {
        var response = api.putForEntity(URL + "/" + UUID.randomUUID(), pensionBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_existingId_returns204AndTheSourceIsGone() {
        var id = createPensionAndGetId();

        var deleteResponse = api.deleteForEntity(URL + "/" + id);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.getForEntity(URL + "/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_unknownId_returns404() {
        var response = api.deleteForEntity(URL + "/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // === tenant isolation ===

    @Test
    void get_incomeSourceOwnedByAnotherTenant_returns404() {
        var id = createPensionAndGetId();
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.getForEntityAs(authHelper.tenant2Token(), URL + "/" + id);

        assertThat(response.getStatusCode())
                .as("a second tenant must not be able to read another tenant's income source")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void list_isScopedToTheCallingTenant() {
        api.postForEntity(URL, pensionBody());
        authHelper.bootstrapSecondTenant(restTemplate);

        var response = api.getListForEntityAs(authHelper.tenant2Token(), URL);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void delete_incomeSourceOwnedByAnotherTenant_returns404AndLeavesItIntact() {
        var id = createPensionAndGetId();
        authHelper.bootstrapSecondTenant(restTemplate);

        var deleteResponse = api.deleteForEntityAs(authHelper.tenant2Token(), URL + "/" + id);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.getForEntity(URL + "/" + id).getStatusCode())
                .as("the owning tenant's source must survive another tenant's delete attempt")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anonymousRequest_isRejected() {
        var response = api.getAnonForEntity(URL);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
