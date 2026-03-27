package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.export.DataExportService;
import com.wealthview.core.export.dto.TenantExportDto;
import com.wealthview.core.holding.dto.HoldingResponse;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DataExportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class DataExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private DataExportService dataExportService;

    @Test
    void exportJson_returnsJsonWithContentDisposition() throws Exception {
        var export = new TenantExportDto(
                List.of(new AccountResponse(UUID.randomUUID(), "Brokerage", "taxable", "Fidelity",
                        BigDecimal.ZERO, OffsetDateTime.now())),
                List.of(),
                List.of(),
                List.of()
        );
        when(dataExportService.exportAsJson(TENANT_ID)).thenReturn(export);

        mockMvc.perform(get("/api/v1/export/json").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"wealthview-export.json\""))
                .andExpect(jsonPath("$.accounts[0].name").value("Brokerage"));
    }

    @Test
    void exportAccountsCsv_returnsCsvWithContentDisposition() throws Exception {
        when(dataExportService.exportAccountsCsv(TENANT_ID)).thenReturn("id,name\n1,Test\n");

        mockMvc.perform(get("/api/v1/export/csv/accounts").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"accounts.csv\""))
                .andExpect(header().string("Content-Type", "text/csv"));
    }

    @Test
    void exportTransactionsCsv_returnsCsv() throws Exception {
        when(dataExportService.exportTransactionsCsv(TENANT_ID)).thenReturn("id,account_id\n");

        mockMvc.perform(get("/api/v1/export/csv/transactions").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"transactions.csv\""));
    }

    @Test
    void exportHoldingsCsv_returnsCsv() throws Exception {
        when(dataExportService.exportHoldingsCsv(TENANT_ID)).thenReturn("id,symbol\n");

        mockMvc.perform(get("/api/v1/export/csv/holdings").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"holdings.csv\""));
    }

    @Test
    void exportPropertiesCsv_returnsCsv() throws Exception {
        when(dataExportService.exportPropertiesCsv(TENANT_ID)).thenReturn("id,address\n");

        mockMvc.perform(get("/api/v1/export/csv/properties").with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"properties.csv\""));
    }

    @Test
    void exportJson_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/export/json"))
                .andExpect(status().isUnauthorized());
    }
}
