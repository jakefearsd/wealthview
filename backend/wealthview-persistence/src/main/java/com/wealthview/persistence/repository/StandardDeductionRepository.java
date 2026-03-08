package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.StandardDeductionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface StandardDeductionRepository extends JpaRepository<StandardDeductionEntity, UUID> {

    Optional<StandardDeductionEntity> findByTaxYearAndFilingStatus(int taxYear, String filingStatus);

    @Query("SELECT MAX(s.taxYear) FROM StandardDeductionEntity s")
    Integer findMaxTaxYear();
}
