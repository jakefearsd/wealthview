package com.wealthview.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.core.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID TXN_ID = UUID.randomUUID();

    private TransactionResponse sampleResponse() {
        return new TransactionResponse(TXN_ID, ACCOUNT_ID, LocalDate.of(2025, 1, 15),
                "buy", "AAPL", new BigDecimal("10"), new BigDecimal("1500.00"), OffsetDateTime.now());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(transactionService.create(eq(TENANT_ID), eq(ACCOUNT_ID), any(TransactionRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-15", "type": "buy", "symbol": "AAPL",
                                 "quantity": 10, "amount": 1500.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.type").value("buy"));
    }

    @Test
    void listByAccount_returns200() throws Exception {
        var page = new PageResponse<>(List.of(sampleResponse()), 0, 25, 1L);
        when(transactionService.listByAccount(eq(TENANT_ID), eq(ACCOUNT_ID), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symbol").value("AAPL"));
    }

    @Test
    void update_validInput_returns200() throws Exception {
        when(transactionService.update(eq(TENANT_ID), eq(TXN_ID), any(TransactionRequest.class)))
                .thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/transactions/{id}", TXN_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-15", "type": "buy", "symbol": "AAPL",
                                 "quantity": 10, "amount": 1500.00}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(transactionService.update(eq(TENANT_ID), eq(TXN_ID), any(TransactionRequest.class)))
                .thenThrow(new EntityNotFoundException("Transaction not found"));

        mockMvc.perform(put("/api/v1/transactions/{id}", TXN_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "2025-01-15", "type": "buy", "symbol": "AAPL",
                                 "quantity": 10, "amount": 1500.00}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void listByAccount_withSymbolFilter_returns200() throws Exception {
        var page = new PageResponse<>(List.of(sampleResponse()), 0, 25, 1L);
        when(transactionService.listByAccountAndSymbol(eq(TENANT_ID), eq(ACCOUNT_ID), eq("AAPL"), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .with(authenticatedAdmin())
                        .param("symbol", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symbol").value("AAPL"));
    }

    @Test
    void delete_existing_returns204() throws Exception {
        doNothing().when(transactionService).delete(TENANT_ID, TXN_ID);

        mockMvc.perform(delete("/api/v1/transactions/{id}", TXN_ID)
                        .with(authenticatedAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void create_missingDate_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{accountId}/transactions", ACCOUNT_ID)
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "buy", "symbol": "AAPL", "amount": 1500.00}
                                """))
                .andExpect(status().isBadRequest());
    }
}
