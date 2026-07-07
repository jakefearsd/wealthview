package com.wealthview.core.auth;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;

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
        if (auth == null) {
            throw new IllegalStateException("No authentication context available");
        }
        // Spring Security 7 marks Authentication#getPrincipal() @Nullable (JSpecify);
        // read it once and null-check the local so the dereference below is guarded.
        var principal = auth.getPrincipal();
        if (principal == null) {
            throw new IllegalStateException("No authentication context available");
        }
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
