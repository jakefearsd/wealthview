package com.wealthview.api.controller;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import tools.jackson.databind.ObjectMapper;

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

@WealthViewControllerTest(ExchangeRateController.class)
class ExchangeRateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ExchangeRateService exchangeRateService;


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
