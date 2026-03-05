package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.AccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    Page<AccountEntity> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<AccountEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
