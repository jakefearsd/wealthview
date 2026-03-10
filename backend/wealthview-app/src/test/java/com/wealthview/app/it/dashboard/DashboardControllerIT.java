package com.wealthview.app.it.dashboard;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.testutil.TestDataHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DashboardControllerIT extends AbstractApiIntegrationTest {

    @BeforeEach
    @Override
    protected void setUp() {
        // Suppress per-test clean/bootstrap; this class uses @BeforeAll + ordered tests
    }

    @BeforeAll
    void setUpData() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);
        var data = new TestDataHelper(restTemplate, authHelper);

        // Create an account with transactions
        var accountId = data.createAccountAndGetId("Dashboard Brokerage", "brokerage");
        data.createBuyTransaction(accountId, "AAPL", 10, 1500);

        // Add a VOO transaction — VOO has seed price data, unlike AAPL
        data.createBuyTransaction(accountId, "VOO", 5, 2000);

        // Create a bank account (should be excluded from investment counts)
        data.createAccount("Dashboard Checking", "bank");

        // Create a property
        var propertyBody = Map.of(
                "address", "Dashboard Property",
                "purchase_price", 300000,
                "purchase_date", "2020-06-01",
                "current_value", 350000,
                "mortgage_balance", 200000
        );
        restTemplate.exchange("/api/v1/properties",
                HttpMethod.POST, authHelper.authEntity(propertyBody, authHelper.adminToken()), MAP_TYPE);
    }

    @Test
    @Order(1)
    void getSummary_withAccountsAndProperties_returnsCorrectNetWorth() {
        var response = restTemplate.exchange("/api/v1/dashboard/summary",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("total_investments", "total_property_equity", "net_worth");
        assertThat(((Number) response.getBody().get("net_worth")).doubleValue()).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void getPortfolioHistory_withData_returns200WithDataPoints() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();
        assertThat(body).containsKeys("data_points", "investment_account_count", "property_count", "weeks");

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) body.get("data_points");
        assertThat(dataPoints).isNotEmpty();
        assertThat(((Number) body.get("investment_account_count")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("property_count")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("weeks")).intValue()).isGreaterThan(0);
    }

    @Test
    @Order(3)
    void getPortfolioHistory_allDatesAreFridays() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) response.getBody().get("data_points");
        assertThat(dataPoints).isNotEmpty();

        for (var dp : dataPoints) {
            var date = LocalDate.parse((String) dp.get("date"));
            assertThat(date.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        }
    }

    @Test
    @Order(4)
    void getPortfolioHistory_datesAreSortedChronologically() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) response.getBody().get("data_points");
        var dates = dataPoints.stream()
                .map(dp -> LocalDate.parse((String) dp.get("date")))
                .toList();

        assertThat(dates).isSorted();
    }

    @Test
    @Order(5)
    void getPortfolioHistory_totalEqualsInvestmentPlusProperty() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) response.getBody().get("data_points");
        assertThat(dataPoints).isNotEmpty();

        for (var dp : dataPoints) {
            double total = ((Number) dp.get("total_value")).doubleValue();
            double investment = ((Number) dp.get("investment_value")).doubleValue();
            double property = ((Number) dp.get("property_equity")).doubleValue();
            assertThat(total).isCloseTo(investment + property, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Test
    @Order(6)
    void getPortfolioHistory_yearsParam_affectsWeekCount() {
        var response1 = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=1",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);
        var response2 = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        int weeks1 = ((Number) response1.getBody().get("weeks")).intValue();
        int weeks2 = ((Number) response2.getBody().get("weeks")).intValue();

        assertThat(weeks1).isBetween(50, 54);
        assertThat(weeks2).isBetween(102, 106);
    }

    @Test
    @Order(7)
    void getPortfolioHistory_bankAccountsExcluded() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=2",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(((Number) response.getBody().get("investment_account_count")).intValue()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void getPortfolioHistory_propertyEquityZeroBeforePurchase() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history?years=10",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) response.getBody().get("data_points");
        var purchaseDate = LocalDate.of(2020, 6, 1);

        var beforePurchase = new ArrayList<Double>();
        var afterPurchase = new ArrayList<Double>();

        for (var dp : dataPoints) {
            var date = LocalDate.parse((String) dp.get("date"));
            double equity = ((Number) dp.get("property_equity")).doubleValue();
            if (date.isBefore(purchaseDate)) {
                beforePurchase.add(equity);
            } else {
                afterPurchase.add(equity);
            }
        }

        assertThat(beforePurchase).isNotEmpty();
        assertThat(beforePurchase).allSatisfy(e -> assertThat(e).isEqualTo(0.0));
        assertThat(afterPurchase).isNotEmpty();
        assertThat(afterPurchase).allSatisfy(e -> assertThat(e).isGreaterThan(0.0));
    }

    @Test
    @Order(9)
    void getPortfolioHistory_unauthenticated_returns403() {
        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history",
                HttpMethod.GET, HttpEntity.EMPTY, MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(100)
    void getSummary_emptyTenant_returnsZeroValues() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);

        var response = restTemplate.exchange("/api/v1/dashboard/summary",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("net_worth")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @Order(101)
    void getPortfolioHistory_emptyTenant_returnsEmptyDataPoints() {
        databaseCleaner.clean();
        authHelper.bootstrap(restTemplate);

        var response = restTemplate.exchange("/api/v1/dashboard/portfolio-history",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = response.getBody();

        @SuppressWarnings("unchecked")
        var dataPoints = (List<Map<String, Object>>) body.get("data_points");
        assertThat(dataPoints).isEmpty();
        assertThat(((Number) body.get("investment_account_count")).intValue()).isEqualTo(0);
        assertThat(((Number) body.get("property_count")).intValue()).isEqualTo(0);
        assertThat(((Number) body.get("weeks")).intValue()).isEqualTo(0);
    }
}
