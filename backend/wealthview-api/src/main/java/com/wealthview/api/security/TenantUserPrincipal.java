package com.wealthview.api.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.wealthview.core.auth.TenantContext;

public record TenantUserPrincipal(
        UUID userId,
        UUID tenantId,
        String email,
        String role,
        UUID sessionId
) implements UserDetails, TenantContext.AuthenticatedUser {

    public TenantUserPrincipal(UUID userId, UUID tenantId, String email, String role) {
        this(userId, tenantId, email, role, null);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.US)));
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
