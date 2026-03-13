package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.importservice.ImportService;
import com.wealthview.core.importservice.PositionImportService;
import com.wealthview.core.importservice.dto.ImportJobResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    private final ImportService importService;
    private final PositionImportService positionImportService;

    public ImportController(ImportService importService,
                            PositionImportService positionImportService) {
        this.importService = importService;
        this.positionImportService = positionImportService;
    }

    @PostMapping("/csv")
    public ResponseEntity<ImportJobResponse> importCsv(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam UUID accountId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String format) throws IOException {
        var result = (format != null && !format.isBlank())
                ? importService.importCsv(principal.tenantId(), accountId, file.getInputStream(), format)
                : importService.importCsv(principal.tenantId(), accountId, file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/positions")
    public ResponseEntity<ImportJobResponse> importPositions(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam UUID accountId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String format) throws IOException {
        var result = positionImportService.importPositions(
                principal.tenantId(), accountId, file.getInputStream(),
                format != null ? format : "fidelityPositions");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/ofx")
    public ResponseEntity<ImportJobResponse> importOfx(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam UUID accountId,
            @RequestParam("file") MultipartFile file) throws IOException {
        var result = importService.importOfx(principal.tenantId(), accountId, file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<ImportJobResponse>> listJobs(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(importService.listJobs(principal.tenantId()));
    }
}
