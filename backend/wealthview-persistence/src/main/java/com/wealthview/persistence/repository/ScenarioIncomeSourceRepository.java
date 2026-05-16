package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;

public interface ScenarioIncomeSourceRepository extends JpaRepository<ScenarioIncomeSourceEntity, UUID> {

    List<ScenarioIncomeSourceEntity> findByScenario_Id(UUID scenarioId);

    @Query("SELECT s FROM ScenarioIncomeSourceEntity s JOIN FETCH s.incomeSource WHERE s.scenario.id = :scenarioId")
    List<ScenarioIncomeSourceEntity> findWithIncomeSourceByScenarioId(@Param("scenarioId") UUID scenarioId);

    void deleteByScenario_IdAndIncomeSource_Id(UUID scenarioId, UUID incomeSourceId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM ScenarioIncomeSourceEntity e WHERE e.scenario.id = :scenarioId")
    void deleteByScenario_Id(UUID scenarioId);
}
