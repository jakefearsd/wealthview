package com.wealthview.app.it.exchangerate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRateControllerIT extends AbstractApiIntegrationTest {

    @Test
    void create_validRate_returns201() {
        var body = Map.of("currency_code", "EUR", "rate_to_usd", 1.08);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("currency_code")).isEqualTo("EUR");
    }

    @Test
    void create_duplicateCurrency_returns409() {
        data.createExchangeRate("GBP", 1.27);

        var body = Map.of("currency_code", "GBP", "rate_to_usd", 1.30);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void create_usdCurrency_returns400() {
        var body = Map.of("currency_code", "USD", "rate_to_usd", 1.0);
        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.POST, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_afterCreating_returnsRates() {
        data.createExchangeRate("EUR", 1.08);
        data.createExchangeRate("GBP", 1.27);

        var response = restTemplate.exchange("/api/v1/exchange-rates",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void update_existingRate_returns200() {
        data.createExchangeRate("EUR", 1.08);

        var body = Map.of("currency_code", "EUR", "rate_to_usd", 1.12);
        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.PUT, authHelper.authEntity(body, authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("rate_to_usd")).doubleValue()).isEqualTo(1.12);
    }

    @Test
    void delete_noAccountsUsingCurrency_returns204() {
        data.createExchangeRate("EUR", 1.08);

        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void delete_accountsUsingCurrency_returns409() {
        data.createExchangeRate("EUR", 1.08);
        data.createAccountWithCurrencyAndGetId("Euro Account", "brokerage", "EUR");

        var response = restTemplate.exchange("/api/v1/exchange-rates/EUR",
                HttpMethod.DELETE, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void accountWithCurrency_returnsCorrectCurrencyField() {
        data.createExchangeRate("EUR", 1.08);
        var accountId = data.createAccountWithCurrencyAndGetId("Euro Brokerage", "brokerage", "EUR");

        var response = restTemplate.exchange("/api/v1/accounts/" + accountId,
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), MAP_TYPE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("currency")).isEqualTo("EUR");
    }
}
