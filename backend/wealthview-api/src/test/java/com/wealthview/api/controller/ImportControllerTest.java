package com.wealthview.api.controller;

import com.wealthview.api.exception.GlobalExceptionHandler;
import com.wealthview.api.security.JwtAuthenticationFilter;
import com.wealthview.api.security.SecurityConfig;
import com.wealthview.api.testutil.TestMetricsConfig;
import com.wealthview.core.auth.JwtTokenProvider;
import com.wealthview.core.auth.SessionStateValidator;
import com.wealthview.core.importservice.ImportService;
import com.wealthview.core.importservice.PositionImportService;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ImportController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtAuthenticationFilter.class, TestMetricsConfig.class})
class ImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImportService importService;

    @MockBean
    private PositionImportService positionImportService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private SessionStateValidator sessionStateValidator;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Test
    void importCsv_validFile_returns201() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                5, 4, 1, "1 rows had parse errors", OffsetDateTime.now());
        when(importService.importCsv(eq(TENANT_ID), eq(ACCOUNT_ID), any()))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "transactions.csv",
                "text/csv", "date,type,symbol,quantity,amount\n2025-01-15,buy,AAPL,10,1500".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.successful_rows").value(4));
    }

    @Test
    void importOfx_validFile_returns201() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "ofx", "completed",
                3, 3, 0, null, OffsetDateTime.now());
        when(importService.importOfx(eq(TENANT_ID), eq(ACCOUNT_ID), any()))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "transactions.ofx",
                "application/x-ofx", "<OFX>test</OFX>".getBytes());

        mockMvc.perform(multipart("/api/v1/import/ofx")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("ofx"))
                .andExpect(jsonPath("$.successful_rows").value(3));
    }

    @Test
    void importPositions_validFile_returns201() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "positions", "completed",
                5, 5, 0, null, OffsetDateTime.now());
        when(positionImportService.importPositions(eq(TENANT_ID), eq(ACCOUNT_ID), any(), eq("fidelityPositions")))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "positions.csv",
                "text/csv", "Account Number,Symbol\nX123,AMZN".getBytes());

        mockMvc.perform(multipart("/api/v1/import/positions")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .param("format", "fidelityPositions")
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("positions"))
                .andExpect(jsonPath("$.successful_rows").value(5));
    }

    @Test
    void listJobs_returns200() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                5, 5, 0, null, OffsetDateTime.now());
        when(importService.listJobs(TENANT_ID)).thenReturn(List.of(jobResponse));

        mockMvc.perform(get("/api/v1/import/jobs")
                        .with(authenticatedAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("completed"));
    }
}
