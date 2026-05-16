package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.TaxBracketEntity;

public interface TaxBracketRepository extends JpaRepository<TaxBracketEntity, UUID> {

    List<TaxBracketEntity> findByTaxYearAndFilingStatusOrderByBracketFloorAsc(int taxYear, String filingStatus);

    @Query("SELECT MAX(t.taxYear) FROM TaxBracketEntity t")
    Integer findMaxTaxYear();
}
