package com.wealthview.core.auth;

import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantFilterActivatorTest {

    private TenantFilterActivator activator;
    private EntityManager entityManager;
    private Session session;
    private Filter filter;

    @BeforeEach
    void setUp() {
        activator = new TenantFilterActivator();
        entityManager = mock(EntityManager.class);
        session = mock(Session.class);
        filter = mock(Filter.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(filter);
        when(filter.setParameter(eq(TenantFilterActivator.PARAM_NAME),
                org.mockito.ArgumentMatchers.any(Object.class))).thenReturn(filter);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enableForCurrentRequest_withMemberPrincipal_enablesFilter() {
        var tenantId = UUID.randomUUID();
        authenticate(new TenantUser(UUID.randomUUID(), tenantId, "u@x.com", "MEMBER"), "ROLE_MEMBER");
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(null);

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isTrue();
        verify(session).enableFilter(TenantFilterActivator.FILTER_NAME);
        verify(filter).setParameter(TenantFilterActivator.PARAM_NAME, tenantId);
    }

    @Test
    void enableForCurrentRequest_withAdminPrincipal_enablesFilter() {
        var tenantId = UUID.randomUUID();
        authenticate(new TenantUser(UUID.randomUUID(), tenantId, "a@x.com", "ADMIN"), "ROLE_ADMIN");
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(null);

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isTrue();
        verify(filter).setParameter(TenantFilterActivator.PARAM_NAME, tenantId);
    }

    @Test
    void enableForCurrentRequest_withSuperAdminRoleString_doesNotEnable() {
        authenticate(new TenantUser(UUID.randomUUID(), UUID.randomUUID(), "s@x.com", "SUPER_ADMIN"),
                "ROLE_SUPER_ADMIN");

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isFalse();
        verify(session, never()).enableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void enableForCurrentRequest_withSuperAdminAuthorityOnly_doesNotEnable() {
        // Defensive: even if role() field disagrees, an authority of SUPER_ADMIN bypasses.
        authenticate(new TenantUser(UUID.randomUUID(), UUID.randomUUID(), "s@x.com", "ADMIN"),
                "ROLE_SUPER_ADMIN");

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isFalse();
        verify(session, never()).enableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void enableForCurrentRequest_withNoAuthentication_doesNotEnable() {
        SecurityContextHolder.clearContext();

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isFalse();
        verify(session, never()).enableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void enableForCurrentRequest_withForeignPrincipalType_doesNotEnable() {
        var auth = new UsernamePasswordAuthenticationToken("not-a-tenant-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isFalse();
    }

    @Test
    void enableForCurrentRequest_withNullTenantId_doesNotEnable() {
        authenticate(new TenantUser(UUID.randomUUID(), null, "u@x.com", "MEMBER"), "ROLE_MEMBER");

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        assertThat(enabled).isFalse();
    }

    @Test
    void enableForCurrentRequest_whenAlreadyEnabled_refreshesParameterAndReturnsFalse() {
        var tenantId = UUID.randomUUID();
        authenticate(new TenantUser(UUID.randomUUID(), tenantId, "u@x.com", "MEMBER"), "ROLE_MEMBER");
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(filter);

        boolean enabled = activator.enableForCurrentRequest(entityManager);

        // Returned false because we did not transition disabled→enabled,
        // but the parameter was refreshed for safety.
        assertThat(enabled).isFalse();
        verify(filter, times(1)).setParameter(TenantFilterActivator.PARAM_NAME, tenantId);
        verify(session, never()).enableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void disable_whenFilterEnabled_disablesAndReturnsTrue() {
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(filter);

        boolean wasEnabled = activator.disable(entityManager);

        assertThat(wasEnabled).isTrue();
        verify(session).disableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void disable_whenFilterNotEnabled_returnsFalseAndIsNoop() {
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(null);

        boolean wasEnabled = activator.disable(entityManager);

        assertThat(wasEnabled).isFalse();
        verify(session, never()).disableFilter(TenantFilterActivator.FILTER_NAME);
    }

    @Test
    void reEnable_withAuthenticatedTenant_re_activatesTheFilter() {
        // reEnable is used after a @CrossTenant bypass to restore the filter;
        // it must actually re-enable for the current tenant, not no-op.
        var tenantId = UUID.randomUUID();
        authenticate(new TenantUser(UUID.randomUUID(), tenantId, "u@x.com", "MEMBER"), "ROLE_MEMBER");
        when(session.getEnabledFilter(TenantFilterActivator.FILTER_NAME)).thenReturn(null);

        activator.reEnable(entityManager);

        verify(session).enableFilter(TenantFilterActivator.FILTER_NAME);
        verify(filter).setParameter(TenantFilterActivator.PARAM_NAME, tenantId);
    }

    @Test
    void reEnable_withNoAuthentication_isNoop() {
        // After logout mid-request there is no authenticated tenant, so reEnable
        // must not attempt to enable the filter.
        SecurityContextHolder.clearContext();

        activator.reEnable(entityManager);

        verify(session, never()).enableFilter(TenantFilterActivator.FILTER_NAME);
    }

    private void authenticate(TenantContext.AuthenticatedUser user, String authority) {
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record TenantUser(UUID userId, UUID tenantId, String email, String role)
            implements TenantContext.AuthenticatedUser {
    }
}
