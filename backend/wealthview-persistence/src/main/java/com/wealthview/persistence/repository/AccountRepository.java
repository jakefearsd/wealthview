package com.wealthview.persistence.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.wealthview.persistence.entity.AccountEntity;

public interface AccountRepository extends TenantScopedRepository<AccountEntity> {

    Page<AccountEntity> findByTenant_Id(UUID tenantId, Pageable pageable);

    long countByTenant_Id(UUID tenantId);
}
