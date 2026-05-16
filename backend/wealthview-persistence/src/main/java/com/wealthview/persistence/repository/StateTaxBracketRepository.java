package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.StateTaxBracketEntity;

public interface StateTaxBracketRepository extends JpaRepository<StateTaxBracketEntity, UUID> {

    List<StateTaxBracketEntity> findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
            String stateCode, int taxYear, String filingStatus);

    @Query("SELECT MAX(t.taxYear) FROM StateTaxBracketEntity t WHERE t.stateCode = :stateCode")
    Integer findMaxTaxYearByStateCode(String stateCode);
}
