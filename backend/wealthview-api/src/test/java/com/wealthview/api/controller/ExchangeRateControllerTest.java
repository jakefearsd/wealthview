package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExchangeRateController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExchangeRateService exchangeRateService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private ExchangeRateResponse eurResponse() {
        return new ExchangeRateResponse("EUR", new BigDecimal("1.0850"), OffsetDateTime.now());
    }

    private ExchangeRateResponse gbpResponse() {
        return new ExchangeRateResponse("GBP", new BigDecimal("1.2700"), OffsetDateTime.now());
    }

    @Test
    void list_returnsAllRates() throws Exception {
        when(exchangeRateService.list(TENANT_ID)).thenReturn(List.of(eurResponse(), gbpResponse()));

        mockMvc.perform(get("/api/v1/exchange-rates")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].currency_code").value("EUR"))
                .andExpect(jsonPath("$[1].currency_code").value("GBP"));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(exchangeRateService.create(eq(TENANT_ID), any(ExchangeRateRequest.class)))
                .thenReturn(eurResponse());

        mockMvc.perform(post("/api/v1/exchange-rates")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 1.0850}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency_code").value("EUR"))
                .andExpect(jsonPath("$.rate_to_usd").value(1.0850));
    }

    @Test
    void create_invalidCurrencyCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "eu", "rate_to_usd": 1.0850}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_zeroRate_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/exchange-rates")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_existingCurrency_returns200() throws Exception {
        when(exchangeRateService.update(eq(TENANT_ID), eq("EUR"), any(BigDecimal.class)))
                .thenReturn(eurResponse());

        mockMvc.perform(put("/api/v1/exchange-rates/{currencyCode}", "EUR")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currency_code": "EUR", "rate_to_usd": 1.0850}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency_code").value("EUR"));
    }

    @Test
    void delete_existingCurrency_returns204() throws Exception {
        doNothing().when(exchangeRateService).delete(TENANT_ID, "EUR");

        mockMvc.perform(delete("/api/v1/exchange-rates/{currencyCode}", "EUR")
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }
}
