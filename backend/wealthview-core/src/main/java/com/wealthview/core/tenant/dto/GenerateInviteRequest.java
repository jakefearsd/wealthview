package com.wealthview.core.tenant.dto;

public record GenerateInviteRequest(
        Integer expiryDays
) {
    public int expiryDaysOrDefault() {
        return expiryDays != null ? expiryDays : 7;
    }
}
