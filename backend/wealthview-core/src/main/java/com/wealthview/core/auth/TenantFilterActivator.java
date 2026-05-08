package com.wealthview.core.auth;

import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

/**
 * Activates / deactivates the {@code tenantFilter} Hibernate filter on the
 * current persistence-context Session based on the authenticated principal.
 *
 * <p>Called by {@code TenantFilterAspect} at the start of every
 * {@code @Transactional} service boundary. The filter is enabled when:
 * <ul>
 *   <li>there is an authenticated principal that exposes a {@code tenantId}, AND</li>
 *   <li>the principal does NOT carry the {@code SUPER_ADMIN} role.</li>
 * </ul>
 *
 * <p>Pre-auth requests (login, register, refresh) and background
 * {@code @Scheduled} jobs run with no SecurityContext — the filter stays
 * disabled, allowing those callers to read across tenants. SUPER_ADMIN
 * sessions also leave the filter disabled so the platform admin can manage
 * every tenant.
 */
@Component
public class TenantFilterActivator {

    public static final String FILTER_NAME = "tenantFilter";
    public static final String PARAM_NAME = "tenantId";
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    private static final Logger log = LoggerFactory.getLogger(TenantFilterActivator.class);

    /**
     * Enable the tenant filter on {@code entityManager}'s Session for the
     * current authenticated tenant, returning {@code true} when the filter
     * was actually enabled (so callers know whether to disable it on exit).
     */
    public boolean enableForCurrentRequest(EntityManager entityManager) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        var principal = auth.getPrincipal();
        if (!(principal instanceof TenantContext.AuthenticatedUser user)) {
            return false;
        }
        if (isSuperAdmin(auth, user)) {
            return false;
        }
        UUID tenantId = user.tenantId();
        if (tenantId == null) {
            return false;
        }
        return enable(entityManager, tenantId);
    }

    /**
     * Forcibly disable the filter for the current Session — used by
     * {@code @CrossTenant} bypasses. Returns {@code true} when the filter
     * was previously enabled (so the bypass can restore it on exit).
     */
    public boolean disable(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);
        Filter filter = session.getEnabledFilter(FILTER_NAME);
        if (filter == null) {
            return false;
        }
        session.disableFilter(FILTER_NAME);
        return true;
    }

    /**
     * Re-enable the filter previously disabled by {@link #disable(EntityManager)}.
     * Reads the current authenticated tenantId again — if there is no longer
     * an authenticated tenant (e.g. logout in the middle of a request, or
     * the principal switched), this becomes a no-op.
     */
    public void reEnable(EntityManager entityManager) {
        enableForCurrentRequest(entityManager);
    }

    private boolean enable(EntityManager entityManager, UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        if (session.getEnabledFilter(FILTER_NAME) != null) {
            // Already enabled (nested transactional method). Refresh the
            // parameter in case the principal changed mid-request.
            session.getEnabledFilter(FILTER_NAME).setParameter(PARAM_NAME, tenantId);
            return false;
        }
        try {
            session.enableFilter(FILTER_NAME).setParameter(PARAM_NAME, tenantId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Could not enable tenantFilter for tenant {}", tenantId, ex);
            return false;
        }
    }

    private boolean isSuperAdmin(Authentication auth, TenantContext.AuthenticatedUser user) {
        if (SUPER_ADMIN_ROLE.equalsIgnoreCase(user.role())) {
            return true;
        }
        for (var ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a == null) continue;
            String upper = a.toUpperCase(Locale.US);
            if (upper.equals("ROLE_" + SUPER_ADMIN_ROLE) || upper.equals(SUPER_ADMIN_ROLE)) {
                return true;
            }
        }
        return false;
    }
}
