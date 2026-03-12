package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ScenarioIncomeSourceRepository extends JpaRepository<ScenarioIncomeSourceEntity, UUID> {

    List<ScenarioIncomeSourceEntity> findByScenario_Id(UUID scenarioId);

    void deleteByScenario_IdAndIncomeSource_Id(UUID scenarioId, UUID incomeSourceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ScenarioIncomeSourceEntity e WHERE e.scenario.id = :scenarioId")
    void deleteByScenario_Id(UUID scenarioId);
}
