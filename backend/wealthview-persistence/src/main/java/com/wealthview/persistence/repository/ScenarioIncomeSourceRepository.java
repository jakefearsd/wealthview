package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScenarioIncomeSourceRepository extends JpaRepository<ScenarioIncomeSourceEntity, UUID> {

    List<ScenarioIncomeSourceEntity> findByScenario_Id(UUID scenarioId);

    void deleteByScenario_IdAndIncomeSource_Id(UUID scenarioId, UUID incomeSourceId);
}
