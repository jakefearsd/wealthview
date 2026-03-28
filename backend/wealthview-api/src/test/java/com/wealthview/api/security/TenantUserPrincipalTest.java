package com.wealthview.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantUserPrincipalTest {

    @Test
    void getAuthorities_adminRole_returnsRoleAdminAuthority() {
        var principal = new TenantUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "admin@test.com", "ADMIN");

        var authorities = principal.getAuthorities();

        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void getAuthorities_userRole_returnsRoleUserAuthority() {
        var principal = new TenantUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com", "USER");

        var authorities = principal.getAuthorities();

        assertThat(authorities).hasSize(1);
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void getUsername_returnsEmail() {
        var email = "jake@wealthview.local";
        var principal = new TenantUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), email, "USER");

        assertThat(principal.getUsername()).isEqualTo(email);
    }

    @Test
    void getAuthorities_lowercaseRole_convertsToUppercase() {
        var principal = new TenantUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com", "admin");

        var authorities = principal.getAuthorities();

        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void getTenantId_returnsCorrectTenantId() {
        var tenantId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var principal = new TenantUserPrincipal(userId, tenantId, "user@test.com", "USER");

        assertThat(principal.tenantId()).isEqualTo(tenantId);
        assertThat(principal.userId()).isEqualTo(userId);
    }

    @Test
    void getPassword_returnsNull() {
        var principal = new TenantUserPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "user@test.com", "USER");

        assertThat(principal.getPassword()).isNull();
    }
}
