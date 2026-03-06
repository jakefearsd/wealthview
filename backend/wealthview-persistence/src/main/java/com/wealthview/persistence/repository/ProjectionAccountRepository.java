package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ProjectionAccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectionAccountRepository extends JpaRepository<ProjectionAccountEntity, UUID> {

    List<ProjectionAccountEntity> findByScenario_Id(UUID scenarioId);
}
