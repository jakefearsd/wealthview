package com.wealthview.api.controller;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.wealthview.api.testutil.WealthViewControllerTest;
import com.wealthview.core.importservice.ImportService;
import com.wealthview.core.importservice.PositionImportService;
import com.wealthview.core.importservice.dto.ImportJobResponse;

import static com.wealthview.api.testutil.ControllerTestUtils.TENANT_ID;
import static com.wealthview.api.testutil.ControllerTestUtils.authenticatedAdmin;
import static com.wealthview.api.testutil.ControllerTestUtils.errorEnvelope;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WealthViewControllerTest(ImportController.class)
class ImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImportService importService;

    @MockitoBean
    private PositionImportService positionImportService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Test
    void importCsv_validFile_returns201() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                5, 4, 1, "1 rows had parse errors", OffsetDateTime.now());
        when(importService.importCsv(eq(TENANT_ID), eq(ACCOUNT_ID), any(), isNull()))
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

    @Test
    void importCsv_withExplicitFormat_usesFormatOverload() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                3, 3, 0, null, OffsetDateTime.now());
        when(importService.importCsv(eq(TENANT_ID), eq(ACCOUNT_ID), any(), eq("schwab")))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "schwab.csv",
                "text/csv", "date,action,symbol,qty,amount\n2025-01-15,Buy,AAPL,10,1500".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .param("format", "schwab")
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void importCsv_blankFormat_usesAutoDetect() throws Exception {
        // blank format string is passed straight through; ImportService.resolveParser treats
        // a blank format as the generic/auto-detect case (format.isBlank() is true)
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                2, 2, 0, null, OffsetDateTime.now());
        when(importService.importCsv(eq(TENANT_ID), eq(ACCOUNT_ID), any(), eq("   ")))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "txns.csv",
                "text/csv", "date,type\n2025-01-15,buy".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .param("format", "   ")
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated());
    }

    @Test
    void importPositions_withNoFormat_usesDefaultFidelityPositions() throws Exception {
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "positions", "completed",
                4, 4, 0, null, OffsetDateTime.now());
        when(positionImportService.importPositions(eq(TENANT_ID), eq(ACCOUNT_ID), any(), eq("fidelityPositions")))
                .thenReturn(jobResponse);

        var file = new MockMultipartFile("file", "positions.csv",
                "text/csv", "Account Number,Symbol\nX123,AMZN".getBytes());

        mockMvc.perform(multipart("/api/v1/import/positions")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        // no format param — defaults to fidelityPositions
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.successful_rows").value(4));
    }

    @Test
    void importCsv_disallowedContentType_returns400() throws Exception {
        var file = new MockMultipartFile("file", "malware.exe",
                "application/x-msdownload", "MZ".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .with(authenticatedAdmin()))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST));
    }

    @Test
    void importCsv_nullContentType_proceeds() throws Exception {
        // null content type passes the type guard (condition short-circuits on null)
        var jobResponse = new ImportJobResponse(UUID.randomUUID(), "csv", "completed",
                1, 1, 0, null, OffsetDateTime.now());
        when(importService.importCsv(eq(TENANT_ID), eq(ACCOUNT_ID), any(), isNull()))
                .thenReturn(jobResponse);

        // MockMultipartFile with no content type → getContentType() returns null
        var file = new MockMultipartFile("file", "transactions.csv",
                null, "date,type\n2025-01-15,buy".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString())
                        .with(authenticatedAdmin()))
                .andExpect(status().isCreated());
    }

    @Test
    void importCsv_unauthenticated_returns403() throws Exception {
        // POST without a CSRF token is denied by the CSRF filter (403) before
        // the auth check, which is the correct layered security behavior.
        var file = new MockMultipartFile("file", "transactions.csv",
                "text/csv", "date,type\n2025-01-15,buy".getBytes());

        mockMvc.perform(multipart("/api/v1/import/csv")
                        .file(file)
                        .param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isForbidden());
    }
}
