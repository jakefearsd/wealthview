package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.LtcgBracketEntity;

public interface LtcgBracketRepository extends JpaRepository<LtcgBracketEntity, UUID> {

    List<LtcgBracketEntity> findByTaxYearAndFilingStatusOrderByBracketFloorAsc(int taxYear, String filingStatus);
}
