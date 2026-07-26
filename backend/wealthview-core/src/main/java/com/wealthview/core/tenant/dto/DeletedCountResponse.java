package com.wealthview.core.tenant.dto;

/**
 * Response body for bulk-delete endpoints that report how many rows were
 * removed. Currently used by {@code TenantManagementController}'s
 * used-invite-code cleanup.
 */
public record DeletedCountResponse(int deleted) {
}
