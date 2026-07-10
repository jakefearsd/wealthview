package com.wealthview.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.SecurityClassOverrideEntity;

public interface SecurityClassOverrideRepository extends JpaRepository<SecurityClassOverrideEntity, UUID> {

    Optional<SecurityClassOverrideEntity> findByTenantIdAndSymbol(UUID tenantId, String symbol);
}
