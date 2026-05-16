package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.wealthview.persistence.entity.StockSplitAdjustmentEntity;

@Repository
public interface StockSplitAdjustmentRepository extends JpaRepository<StockSplitAdjustmentEntity, UUID> {

    List<StockSplitAdjustmentEntity> findBySplit_Id(UUID splitId);

    List<StockSplitAdjustmentEntity> findBySplit_IdAndTenantId(UUID splitId, UUID tenantId);

    long countBySplit_Id(UUID splitId);
}
