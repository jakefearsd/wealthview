package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.IncomeSourceEntity;

public interface IncomeSourceRepository extends JpaRepository<IncomeSourceEntity, UUID> {

    List<IncomeSourceEntity> findByTenant_IdOrderByCreatedAtDesc(UUID tenantId);

    Optional<IncomeSourceEntity> findByTenant_IdAndId(UUID tenantId, UUID id);

    void deleteByTenant_IdAndId(UUID tenantId, UUID id);

    List<IncomeSourceEntity> findByTenant_IdAndProperty_Id(UUID tenantId, UUID propertyId);
}
