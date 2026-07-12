package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.IrmaaTierEntity;

public interface IrmaaTierRepository extends JpaRepository<IrmaaTierEntity, UUID> {

    List<IrmaaTierEntity> findByTaxYearAndFilingStatusOrderByMagiFloorAsc(int taxYear, String filingStatus);

    @Query("SELECT MAX(i.taxYear) FROM IrmaaTierEntity i")
    Integer findMaxTaxYear();
}
