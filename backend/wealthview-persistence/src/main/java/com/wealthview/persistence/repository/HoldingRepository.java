package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.HoldingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<HoldingEntity, UUID> {

    Optional<HoldingEntity> findByAccountIdAndSymbol(UUID accountId, String symbol);

    List<HoldingEntity> findByAccountIdAndTenantId(UUID accountId, UUID tenantId);

    List<HoldingEntity> findByTenantId(UUID tenantId);

    Optional<HoldingEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
