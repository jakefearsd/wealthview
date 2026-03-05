package com.wealthview.core.auth;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class TenantContext {

    private TenantContext() {
    }

    public static UUID getCurrentTenantId() {
        return getAuthenticatedUser().tenantId();
    }

    public static UUID getCurrentUserId() {
        return getAuthenticatedUser().userId();
    }

    public static String getCurrentRole() {
        return getAuthenticatedUser().role();
    }

    private static AuthenticatedUser getAuthenticatedUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new IllegalStateException("No authentication context available");
        }
        var principal = auth.getPrincipal();
        if (principal instanceof AuthenticatedUser user) {
            return user;
        }
        throw new IllegalStateException("Unexpected principal type: " + principal.getClass());
    }

    public interface AuthenticatedUser {
        UUID userId();
        UUID tenantId();
        String role();
    }
}
