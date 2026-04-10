package com.wealthview.api.controller;

import com.wealthview.api.common.PageRequests;
import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.audit.AuditLogService;
import com.wealthview.core.audit.dto.AuditLogResponse;
import com.wealthview.core.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<AuditLogResponse>> getAuditLogs(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(name = "entity_type", required = false) String entityType) {
        var response = auditLogService.getAuditLogs(
                principal.tenantId(), entityType, PageRequest.of(page, PageRequests.clampSize(size)));
        return ResponseEntity.ok(response);
    }
}
