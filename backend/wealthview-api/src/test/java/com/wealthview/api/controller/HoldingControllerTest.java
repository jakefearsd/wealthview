package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.holding.HoldingService;
import com.wealthview.core.holding.dto.HoldingRequest;
import com.wealthview.core.holding.dto.HoldingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HoldingController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class})
class HoldingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HoldingService holdingService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID HOLDING_ID = UUID.randomUUID();

    private HoldingResponse sampleResponse() {
        return new HoldingResponse(HOLDING_ID, ACCOUNT_ID, "AAPL",
                new BigDecimal("10"), new BigDecimal("1500.00"), false, false, null, LocalDate.now());
    }

    @Test
    void listByAccount_returns200() throws Exception {
        when(holdingService.listByAccount(TENANT_ID, ACCOUNT_ID))
                .thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/holdings", ACCOUNT_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"));
    }

    @Test
    void createManual_validInput_returns201() throws Exception {
        when(holdingService.createManual(eq(TENANT_ID), any(HoldingRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/holdings")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id": "%s", "symbol": "AAPL",
                                 "quantity": 10, "cost_basis": 1500.00}
                                """.formatted(ACCOUNT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"));
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(holdingService.update(eq(TENANT_ID), eq(HOLDING_ID), any(HoldingRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/holdings/{id}", HOLDING_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id": "%s", "symbol": "AAPL",
                                 "quantity": 15, "cost_basis": 2250.00}
                                """.formatted(ACCOUNT_ID)))
                .andExpect(status().isOk());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(holdingService.update(eq(TENANT_ID), eq(HOLDING_ID), any(HoldingRequest.class)))
                .thenThrow(new EntityNotFoundException("Holding not found"));

        mockMvc.perform(put("/api/v1/holdings/{id}", HOLDING_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id": "%s", "symbol": "AAPL",
                                 "quantity": 15, "cost_basis": 2250.00}
                                """.formatted(ACCOUNT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void createManual_missingSymbol_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/holdings")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id": "%s", "quantity": 10, "cost_basis": 1500.00}
                                """.formatted(ACCOUNT_ID)))
                .andExpect(status().isBadRequest());
    }
}
