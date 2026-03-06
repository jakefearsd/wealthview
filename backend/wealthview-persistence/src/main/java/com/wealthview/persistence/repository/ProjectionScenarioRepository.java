package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectionScenarioRepository extends JpaRepository<ProjectionScenarioEntity, UUID> {

    Optional<ProjectionScenarioEntity> findByTenant_IdAndId(UUID tenantId, UUID id);

    List<ProjectionScenarioEntity> findByTenant_IdOrderByCreatedAtDesc(UUID tenantId);

    void deleteByTenant_IdAndId(UUID tenantId, UUID id);
}
