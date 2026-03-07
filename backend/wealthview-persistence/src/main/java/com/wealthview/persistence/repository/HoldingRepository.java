package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.HoldingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldingRepository extends JpaRepository<HoldingEntity, UUID> {

    Optional<HoldingEntity> findByAccount_IdAndSymbol(UUID accountId, String symbol);

    List<HoldingEntity> findByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId);

    List<HoldingEntity> findByTenant_Id(UUID tenantId);

    Optional<HoldingEntity> findByIdAndTenant_Id(UUID id, UUID tenantId);

    @Query("SELECT DISTINCT h.symbol FROM HoldingEntity h WHERE h.quantity > 0")
    List<String> findDistinctSymbols();

    void deleteByAccount_IdAndTenant_Id(UUID accountId, UUID tenantId);
}
