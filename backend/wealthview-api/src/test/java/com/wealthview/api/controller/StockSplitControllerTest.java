package com.wealthview.api.controller;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.split.StockSplitService;
import com.wealthview.core.split.StockSplitSyncService;
import com.wealthview.core.split.dto.SplitSyncResult;
import com.wealthview.persistence.entity.StockSplitEntity;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedSuperAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockSplitController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class StockSplitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StockSplitService stockSplitService;

    @MockitoBean
    private StockSplitSyncService stockSplitSyncService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private SessionStateValidator sessionStateValidator;

    private static final UUID SPLIT_ID = UUID.randomUUID();

    @Test
    void listForTenant_authenticated_returns200() throws Exception {
        when(stockSplitService.listForTenant(eq(TENANT_ID), isNull(), isNull(), isNull()))
                .thenReturn(List.of(buildSplitEntity()));

        mockMvc.perform(get("/api/v1/stock-splits")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"));
    }

    @Test
    void listForTenant_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/stock-splits"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_validInput_returns201() throws Exception {
        when(stockSplitService.applySplit(eq("AAPL"), eq(LocalDate.of(2020, 8, 31)),
                eq(4), eq(1), eq("manual")))
                .thenReturn(buildSplitEntity());

        mockMvc.perform(post("/api/v1/admin/stock-splits")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "AAPL",
                                    "effective_date": "2020-08-31",
                                    "numerator": 4,
                                    "denominator": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.numerator").value(4));
    }

    @Test
    void create_missingSymbol_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/stock-splits")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "effective_date": "2020-08-31",
                                    "numerator": 4,
                                    "denominator": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void create_negativeDenominator_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/stock-splits")
                        .with(authenticatedSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "AAPL",
                                    "effective_date": "2020-08-31",
                                    "numerator": 4,
                                    "denominator": -1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void create_nonSuperAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/stock-splits")
                        .with(authenticatedAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "symbol": "AAPL",
                                    "effective_date": "2020-08-31",
                                    "numerator": 4,
                                    "denominator": 1
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void unapply_existing_returns204() throws Exception {
        doNothing().when(stockSplitService).unapplySplit(SPLIT_ID);

        mockMvc.perform(delete("/api/v1/admin/stock-splits/{id}", SPLIT_ID)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void unapply_notFound_returns404() throws Exception {
        doThrow(new EntityNotFoundException("Split not found"))
                .when(stockSplitService).unapplySplit(SPLIT_ID);

        mockMvc.perform(delete("/api/v1/admin/stock-splits/{id}", SPLIT_ID)
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void sync_whenServiceAvailable_returns200() throws Exception {
        var result = new SplitSyncResult(10, 2, 2, List.of());
        when(stockSplitSyncService.syncAll()).thenReturn(result);

        mockMvc.perform(post("/api/v1/admin/stock-splits/sync")
                        .with(authenticatedSuperAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols_scanned").value(10))
                .andExpect(jsonPath("$.splits_applied").value(2));
    }

    private StockSplitEntity buildSplitEntity() {
        return new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "manual");
    }
}
