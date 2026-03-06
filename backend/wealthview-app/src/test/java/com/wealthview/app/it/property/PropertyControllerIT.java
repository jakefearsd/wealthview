package com.wealthview.app.it.property;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyControllerIT extends AbstractApiIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
    }

    @Test
    void create_withoutLoanDetails_returns201WithManualBalance() {
        var body = Map.of(
                "address", "123 Main St",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );

        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) response.getBody().get("mortgage_balance")).intValue()).isEqualTo(200000);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(false);
    }

    @Test
    void create_withFullLoanDetails_returnsComputedBalance() {
        var body = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "loan_amount", 320000,
                "annual_interest_rate", 0.065,
                "loan_term_months", 360,
                "loan_start_date", "2020-01-01",
                "use_computed_balance", true
        );

        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(true);
        assertThat(response.getBody().get("mortgage_balance")).isNotNull();
    }

    @Test
    void get_existingProperty_returnsLoanFields() {
        var id = createPropertyAndGetId();

        var response = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("address")).isEqualTo("123 Main St");
    }

    @Test
    void update_toggleComputedBalanceOn_changesEquity() {
        var id = createPropertyWithLoanAndGetId();
        var updateBody = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "loan_amount", 320000,
                "annual_interest_rate", 0.065,
                "loan_term_months", 360,
                "loan_start_date", "2020-01-01",
                "use_computed_balance", true
        );

        var response = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(true);
    }

    @Test
    void update_toggleComputedBalanceOff_revertsToManual() {
        var id = createPropertyWithLoanAndGetId();
        var updateBody = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "mortgage_balance", 250000,
                "use_computed_balance", false
        );

        var response = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(false);
        assertThat(((Number) response.getBody().get("mortgage_balance")).intValue()).isEqualTo(250000);
    }

    @Test
    void create_withPartialLoanDetails_returns400() {
        var body = Map.of(
                "address", "789 Elm St",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "loan_amount", 240000,
                "use_computed_balance", true
        );

        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withZeroInterestLoan_computesCorrectly() {
        var body = Map.of(
                "address", "Zero Interest Lane",
                "purchase_price", 200000,
                "purchase_date", "2020-01-01",
                "current_value", 220000,
                "loan_amount", 160000,
                "annual_interest_rate", 0.0,
                "loan_term_months", 360,
                "loan_start_date", "2020-01-01",
                "use_computed_balance", true
        );

        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("mortgage_balance")).isNotNull();
    }

    @Test
    void create_withPaidOffLoan_returnsZeroBalance() {
        var body = Map.of(
                "address", "Paid Off Blvd",
                "purchase_price", 100000,
                "purchase_date", "1990-01-01",
                "current_value", 250000,
                "loan_amount", 80000,
                "annual_interest_rate", 0.08,
                "loan_term_months", 360,
                "loan_start_date", "1990-01-01",
                "use_computed_balance", true
        );

        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) response.getBody().get("mortgage_balance")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    void addIncome_validInput_returns201() {
        var propertyId = createPropertyAndGetId();
        var body = Map.of(
                "date", "2024-01-15",
                "amount", 2000,
                "category", "rent",
                "description", "January rent"
        );

        var response = restTemplate.exchange("/api/v1/properties/" + propertyId + "/income",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addExpense_validInput_returns201() {
        var propertyId = createPropertyAndGetId();
        var body = Map.of(
                "date", "2024-01-20",
                "amount", 500,
                "category", "maintenance",
                "description", "Plumbing repair"
        );

        var response = restTemplate.exchange("/api/v1/properties/" + propertyId + "/expenses",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void getCashFlow_returns200() {
        var propertyId = createPropertyAndGetId();
        var incomeBody = Map.of("date", "2024-01-15", "amount", 2000, "category", "rent");
        restTemplate.exchange("/api/v1/properties/" + propertyId + "/income",
                HttpMethod.POST, authHelper.authEntity(incomeBody, authHelper.adminToken()), Void.class);

        var response = restTemplate.exchange(
                "/api/v1/properties/" + propertyId + "/cashflow?from=2024-01&to=2024-12",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void getValuations_nonExistentProperty_returns404() {
        var response = restTemplate.exchange(
                "/api/v1/properties/" + UUID.randomUUID() + "/valuations",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fullCrud_createListGetUpdateDelete() {
        // Create
        var createBody = Map.of(
                "address", "CRUD Test St",
                "purchase_price", 500000,
                "purchase_date", "2022-01-01",
                "current_value", 550000,
                "mortgage_balance", 400000
        );
        var createResponse = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(createBody, authHelper.adminToken()), MAP_TYPE);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = (String) createResponse.getBody().get("id");

        // List
        var listResponse = restTemplate.exchange("/api/v1/properties",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        // Get
        var getResponse = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Update
        var updateBody = Map.of(
                "address", "CRUD Test St Updated",
                "purchase_price", 500000,
                "purchase_date", "2022-01-01",
                "current_value", 600000,
                "mortgage_balance", 380000
        );
        var updateResponse = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.PUT, authHelper.authEntity(updateBody, authHelper.adminToken()), MAP_TYPE);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().get("address")).isEqualTo("CRUD Test St Updated");

        // Delete
        var deleteResponse = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify deleted
        var afterDelete = restTemplate.exchange("/api/v1/properties/" + id,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String createPropertyAndGetId() {
        var body = Map.of(
                "address", "123 Main St",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );
        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }

    private String createPropertyWithLoanAndGetId() {
        var body = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "loan_amount", 320000,
                "annual_interest_rate", 0.065,
                "loan_term_months", 360,
                "loan_start_date", "2020-01-01",
                "use_computed_balance", false
        );
        var response = restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);
        return (String) response.getBody().get("id");
    }
}
