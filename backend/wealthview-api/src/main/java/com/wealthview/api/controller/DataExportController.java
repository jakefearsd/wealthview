package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.export.DataExportService;
import com.wealthview.core.export.dto.TenantExportDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/export")
public class DataExportController {

    private final DataExportService dataExportService;

    public DataExportController(DataExportService dataExportService) {
        this.dataExportService = dataExportService;
    }

    @GetMapping("/json")
    public ResponseEntity<TenantExportDto> exportJson(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        var export = dataExportService.exportAsJson(principal.tenantId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wealthview-export.json\"")
                .body(export);
    }

    @GetMapping("/csv/accounts")
    public ResponseEntity<String> exportAccountsCsv(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return csvResponse(dataExportService.exportAccountsCsv(principal.tenantId()), "accounts.csv");
    }

    @GetMapping("/csv/transactions")
    public ResponseEntity<String> exportTransactionsCsv(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return csvResponse(dataExportService.exportTransactionsCsv(principal.tenantId()), "transactions.csv");
    }

    @GetMapping("/csv/holdings")
    public ResponseEntity<String> exportHoldingsCsv(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return csvResponse(dataExportService.exportHoldingsCsv(principal.tenantId()), "holdings.csv");
    }

    @GetMapping("/csv/properties")
    public ResponseEntity<String> exportPropertiesCsv(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return csvResponse(dataExportService.exportPropertiesCsv(principal.tenantId()), "properties.csv");
    }

    private ResponseEntity<String> csvResponse(String csv, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
