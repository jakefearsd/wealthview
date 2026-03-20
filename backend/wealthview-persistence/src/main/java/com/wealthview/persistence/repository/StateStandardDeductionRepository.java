package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.StateStandardDeductionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StateStandardDeductionRepository extends JpaRepository<StateStandardDeductionEntity, UUID> {

    Optional<StateStandardDeductionEntity> findByStateCodeAndTaxYearAndFilingStatus(
            String stateCode, int taxYear, String filingStatus);

    @Query("SELECT MAX(s.taxYear) FROM StateStandardDeductionEntity s WHERE s.stateCode = :stateCode")
    Integer findMaxTaxYearByStateCode(String stateCode);
}
