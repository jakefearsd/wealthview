package com.wealthview.app.it.property;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.wealthview.app.it.AbstractApiIntegrationTest;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

class PropertyControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_withoutLoanDetails_returns201WithManualBalance() {
        var body = Map.of(
                "address", "123 Main St",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );

        var response = api.postForEntity("/api/v1/properties", body);

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

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(true);
        assertThat(response.getBody().get("mortgage_balance")).isNotNull();
    }

    @Test
    void get_existingProperty_returnsLoanFields() {
        var id = data.createPropertyAndGetId();

        var response = api.getForEntity("/api/v1/properties/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("address")).isEqualTo("123 Main St");
    }

    @Test
    void update_toggleComputedBalanceOn_changesEquity() {
        var id = data.createPropertyWithLoanAndGetId();
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

        var response = api.putForEntity("/api/v1/properties/" + id, updateBody);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("use_computed_balance")).isEqualTo(true);
    }

    @Test
    void update_toggleComputedBalanceOff_revertsToManual() {
        var id = data.createPropertyWithLoanAndGetId();
        var updateBody = Map.of(
                "address", "456 Oak Ave",
                "purchase_price", 400000,
                "purchase_date", "2020-01-01",
                "current_value", 450000,
                "mortgage_balance", 250000,
                "use_computed_balance", false
        );

        var response = api.putForEntity("/api/v1/properties/" + id, updateBody);

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

        var response = api.postForEntity("/api/v1/properties", body);

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

        var response = api.postForEntity("/api/v1/properties", body);

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

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) response.getBody().get("mortgage_balance")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    void addIncome_validInput_returns201() {
        var propertyId = data.createPropertyAndGetId();
        var body = Map.of(
                "date", "2024-01-15",
                "amount", 2000,
                "category", "rent",
                "description", "January rent"
        );

        var response = api.postForEntity("/api/v1/properties/" + propertyId + "/income", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addExpense_validInput_returns201() {
        var propertyId = data.createPropertyAndGetId();
        var body = Map.of(
                "date", "2024-01-20",
                "amount", 500,
                "category", "maintenance",
                "description", "Plumbing repair"
        );

        var response = api.postForEntity("/api/v1/properties/" + propertyId + "/expenses", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void getCashFlow_returns200() {
        var propertyId = data.createPropertyAndGetId();
        var incomeBody = Map.of("date", "2024-01-15", "amount", 2000, "category", "rent");
        api.post("/api/v1/properties/" + propertyId + "/income", incomeBody);

        var response = api.getListForEntity(
                "/api/v1/properties/" + propertyId + "/cashflow?from=2024-01&to=2024-12");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void getValuations_nonExistentProperty_returns404() {
        var response = api.getForEntity("/api/v1/properties/" + UUID.randomUUID() + "/valuations");

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
        var createResponse = api.postForEntity("/api/v1/properties", createBody);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = (String) createResponse.getBody().get("id");

        // List
        var listResponse = api.getListForEntity("/api/v1/properties");
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(1);

        // Get
        var getResponse = api.getForEntity("/api/v1/properties/" + id);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Update
        var updateBody = Map.of(
                "address", "CRUD Test St Updated",
                "purchase_price", 500000,
                "purchase_date", "2022-01-01",
                "current_value", 600000,
                "mortgage_balance", 380000
        );
        var updateResponse = api.putForEntity("/api/v1/properties/" + id, updateBody);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResponse.getBody().get("address")).isEqualTo("CRUD Test St Updated");

        // Delete
        var deleteResponse = api.deleteForEntity("/api/v1/properties/" + id);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Verify deleted
        var afterDelete = api.getForEntity("/api/v1/properties/" + id);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAnalytics_primaryResidenceWithLoan_returnsEquityAndMortgageProgress() {
        var body = Map.ofEntries(
                entry("address", "Analytics Test Home"),
                entry("purchase_price", 300000),
                entry("purchase_date", "2020-01-01"),
                entry("current_value", 350000),
                entry("mortgage_balance", 200000),
                entry("loan_amount", 240000),
                entry("annual_interest_rate", 0.06),
                entry("loan_term_months", 360),
                entry("loan_start_date", "2020-01-01"),
                entry("use_computed_balance", true),
                entry("property_type", "primary_residence")
        );
        var id = (String) api.post("/api/v1/properties", body).get("id");

        var response = api.getForEntity("/api/v1/properties/" + id + "/analytics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("property_type")).isEqualTo("primary_residence");
        assertThat(((Number) response.getBody().get("total_appreciation")).intValue()).isEqualTo(50000);
        assertThat(response.getBody().get("mortgage_progress")).isNotNull();
        assertThat(response.getBody().get("equity_growth")).isNotNull();
        assertThat((List<?>) response.getBody().get("equity_growth")).isNotEmpty();
        // Investment fields should be null
        assertThat(response.getBody().get("cap_rate")).isNull();
        assertThat(response.getBody().get("cash_on_cash_return")).isNull();
    }

    @Test
    void getAnalytics_investmentWithIncome_returnsCapRateAndCashOnCash() {
        // Create investment property
        var body = Map.ofEntries(
                entry("address", "Investment Analytics St"),
                entry("purchase_price", 350000),
                entry("purchase_date", "2023-01-01"),
                entry("current_value", 400000),
                entry("mortgage_balance", 250000),
                entry("loan_amount", 280000),
                entry("annual_interest_rate", 0.07),
                entry("loan_term_months", 360),
                entry("loan_start_date", "2023-01-01"),
                entry("use_computed_balance", false),
                entry("property_type", "investment")
        );
        var createResponse = api.postForEntity("/api/v1/properties", body);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = (String) createResponse.getBody().get("id");

        // Add rent income
        var incomeBody = Map.of("date", "2025-06-15", "amount", 2500, "category", "rent");
        api.post("/api/v1/properties/" + id + "/income", incomeBody);

        // Add operating expense (tax)
        var taxBody = Map.of("date", "2025-06-20", "amount", 500, "category", "tax");
        api.post("/api/v1/properties/" + id + "/expenses", taxBody);

        // Add mortgage expense
        var mortgageBody = Map.of("date", "2025-06-01", "amount", 1800, "category", "mortgage");
        api.post("/api/v1/properties/" + id + "/expenses", mortgageBody);

        var response = api.getForEntity("/api/v1/properties/" + id + "/analytics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("property_type")).isEqualTo("investment");
        assertThat(response.getBody().get("cap_rate")).isNotNull();
        assertThat(response.getBody().get("annual_noi")).isNotNull();
        assertThat(response.getBody().get("cash_on_cash_return")).isNotNull();
        assertThat(response.getBody().get("annual_net_cash_flow")).isNotNull();
        // Cash invested = 350000 - 280000 = 70000
        assertThat(((Number) response.getBody().get("total_cash_invested")).intValue()).isEqualTo(70000);
    }

    @Test
    void getAnalytics_nonExistentProperty_returns404() {
        var response = api.getForEntity("/api/v1/properties/" + UUID.randomUUID() + "/analytics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void create_withPropertyType_returnsPropertyType() {
        var body = Map.of(
                "address", "Investment Property",
                "purchase_price", 400000,
                "purchase_date", "2023-01-01",
                "current_value", 420000,
                "mortgage_balance", 300000,
                "property_type", "investment"
        );

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("property_type")).isEqualTo("investment");
    }

    @Test
    void create_withoutPropertyType_defaultsToPrimaryResidence() {
        var body = Map.of(
                "address", "Default Type Property",
                "purchase_price", 250000,
                "purchase_date", "2023-06-01",
                "current_value", 260000,
                "mortgage_balance", 180000
        );

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("property_type")).isEqualTo("primary_residence");
    }

    @Test
    void getAnalytics_withYearParam_returns200() {
        var body = Map.of(
                "address", "Year Param Test",
                "purchase_price", 300000,
                "purchase_date", "2023-01-01",
                "current_value", 320000,
                "mortgage_balance", 200000,
                "property_type", "investment"
        );
        var id = (String) api.post("/api/v1/properties", body).get("id");

        var response = api.getForEntity("/api/v1/properties/" + id + "/analytics?year=2025");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("property_type")).isEqualTo("investment");
        assertThat(response.getBody().get("cap_rate")).isNotNull();
    }

    @Test
    void getAnalytics_vacationProperty_noInvestmentMetrics() {
        var body = Map.of(
                "address", "Beach House",
                "purchase_price", 500000,
                "purchase_date", "2022-06-01",
                "current_value", 550000,
                "mortgage_balance", 300000,
                "property_type", "vacation"
        );
        var id = (String) api.post("/api/v1/properties", body).get("id");

        var response = api.getForEntity("/api/v1/properties/" + id + "/analytics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("property_type")).isEqualTo("vacation");
        assertThat(response.getBody().get("cap_rate")).isNull();
        assertThat(response.getBody().get("total_appreciation")).isNotNull();
        assertThat(((Number) response.getBody().get("total_appreciation")).intValue()).isEqualTo(50000);
    }

    @Test
    void getAnalytics_propertyWithoutLoan_noMortgageProgress() {
        var body = Map.of(
                "address", "Cash Purchase Home",
                "purchase_price", 200000,
                "purchase_date", "2023-01-01",
                "current_value", 220000
        );
        var id = (String) api.post("/api/v1/properties", body).get("id");

        var response = api.getForEntity("/api/v1/properties/" + id + "/analytics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("mortgage_progress")).isNull();
        assertThat(response.getBody().get("equity_growth")).isNotNull();
    }

    @Test
    void create_withInvalidPropertyType_returns400() {
        var body = Map.of(
                "address", "Bad Type Property",
                "purchase_price", 200000,
                "purchase_date", "2023-01-01",
                "current_value", 210000,
                "property_type", "commercial"
        );

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_withFinancialFields_persistsAndReturnsFields() {
        var body = Map.ofEntries(
                entry("address", "Financial Fields Test"),
                entry("purchase_price", 400000),
                entry("purchase_date", "2023-01-01"),
                entry("current_value", 420000),
                entry("mortgage_balance", 300000),
                entry("property_type", "investment"),
                entry("annual_appreciation_rate", 0.03),
                entry("annual_property_tax", 4500),
                entry("annual_insurance_cost", 1800)
        );

        var createResponse = api.postForEntity("/api/v1/properties", body);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) createResponse.getBody().get("annual_appreciation_rate")).doubleValue()).isEqualTo(0.03);
        assertThat(((Number) createResponse.getBody().get("annual_property_tax")).intValue()).isEqualTo(4500);
        assertThat(((Number) createResponse.getBody().get("annual_insurance_cost")).intValue()).isEqualTo(1800);

        // Verify persistence via GET
        var id = (String) createResponse.getBody().get("id");
        var getResponse = api.getForEntity("/api/v1/properties/" + id);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) getResponse.getBody().get("annual_appreciation_rate")).doubleValue()).isEqualTo(0.03);
        assertThat(((Number) getResponse.getBody().get("annual_property_tax")).intValue()).isEqualTo(4500);
        assertThat(((Number) getResponse.getBody().get("annual_insurance_cost")).intValue()).isEqualTo(1800);
    }

    @Test
    void create_withoutFinancialFields_returnsNulls() {
        var body = Map.of(
                "address", "No Financial Fields",
                "purchase_price", 300000,
                "purchase_date", "2023-01-01",
                "current_value", 310000
        );

        var response = api.postForEntity("/api/v1/properties", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("annual_appreciation_rate")).isNull();
        assertThat(response.getBody().get("annual_property_tax")).isNull();
        assertThat(response.getBody().get("annual_insurance_cost")).isNull();
    }

    @Test
    void refreshValuation_zillowDisabled_returns503() {
        var propertyId = data.createPropertyAndGetId();

        var response = api.postForEntity(
                "/api/v1/properties/" + propertyId + "/valuations/refresh", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void selectZpid_zillowDisabled_returns503() {
        var propertyId = data.createPropertyAndGetId();
        var body = Map.of("zpid", "12345");

        var response = api.postForEntity(
                "/api/v1/properties/" + propertyId + "/valuations/select-zpid", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
