package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Base repository for entities that are directly owned by a tenant (a {@code tenant} association
 * navigable by that property path). Declares the two tenant-scoped finders that were previously
 * duplicated verbatim across several repositories.
 */
@NoRepositoryBean
public interface TenantScopedRepository<T> extends JpaRepository<T, UUID> {

    Optional<T> findByTenant_IdAndId(UUID tenantId, UUID id);

    List<T> findByTenant_Id(UUID tenantId);
}
