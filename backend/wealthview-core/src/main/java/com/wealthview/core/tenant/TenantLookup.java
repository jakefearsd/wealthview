package com.wealthview.core.tenant;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.TenantRepository;

/**
 * Resolves the authenticated tenant for service methods. A missing tenant means
 * the JWT refers to a tenant that no longer exists (deleted, or a stale token),
 * so the failure is an invalid-session problem — not a 404 — by policy.
 */
@Component
public class TenantLookup {

    private final TenantRepository tenantRepository;

    public TenantLookup(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public TenantEntity requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));
    }
}
