package com.wealthview.persistence.projection;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MortalityRateRepository extends JpaRepository<MortalityRateEntity, UUID> {

    List<MortalityRateEntity> findAllBySexOrderByAgeAsc(String sex);
}
