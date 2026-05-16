package com.wealthview.core.auth;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private record TenantUser(UUID userId, UUID tenantId, String role)
            implements TenantContext.AuthenticatedUser {
    }

    private void authenticate(TenantContext.AuthenticatedUser user) {
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void getCurrentTenantId_withAuthenticatedUser_returnsTenantIdFromPrincipal() {
        var tenantId = UUID.randomUUID();
        authenticate(new TenantUser(UUID.randomUUID(), tenantId, "member"));

        assertThat(TenantContext.getCurrentTenantId()).isEqualTo(tenantId);
    }

    @Test
    void getCurrentUserId_withAuthenticatedUser_returnsUserIdFromPrincipal() {
        var userId = UUID.randomUUID();
        authenticate(new TenantUser(userId, UUID.randomUUID(), "member"));

        assertThat(TenantContext.getCurrentUserId()).isEqualTo(userId);
    }

    @Test
    void getCurrentRole_withAuthenticatedUser_returnsRoleFromPrincipal() {
        authenticate(new TenantUser(UUID.randomUUID(), UUID.randomUUID(), "admin"));

        assertThat(TenantContext.getCurrentRole()).isEqualTo("admin");
    }

    @Test
    void getCurrentTenantId_withNoAuthentication_throwsIllegalState() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(TenantContext::getCurrentTenantId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authentication context");
    }

    @Test
    void getCurrentUserId_withForeignPrincipalType_throwsIllegalState() {
        // A principal that is not an AuthenticatedUser must be rejected, not
        // coerced — otherwise tenant scoping silently breaks.
        var auth = new UsernamePasswordAuthenticationToken("plain-string-principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThatThrownBy(TenantContext::getCurrentUserId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected principal type");
    }
}
