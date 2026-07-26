package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.PropertyEntity;

public interface PropertyRepository extends TenantScopedRepository<PropertyEntity> {

    @Query("SELECT DISTINCT p.tenant.id FROM PropertyEntity p")
    List<UUID> findDistinctTenantIds();
}
