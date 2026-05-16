package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.ProjectionAccountEntity;

public interface ProjectionAccountRepository extends JpaRepository<ProjectionAccountEntity, UUID> {

    List<ProjectionAccountEntity> findByScenario_Id(UUID scenarioId);
}
