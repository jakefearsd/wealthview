package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.StateTaxSurchargeEntity;

public interface StateTaxSurchargeRepository extends JpaRepository<StateTaxSurchargeEntity, UUID> {

    List<StateTaxSurchargeEntity> findByStateCodeAndTaxYearAndFilingStatus(
            String stateCode, int taxYear, String filingStatus);

    @Query("SELECT MAX(s.taxYear) FROM StateTaxSurchargeEntity s WHERE s.stateCode = :stateCode")
    Integer findMaxTaxYearByStateCode(String stateCode);
}
