package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardrailSpendingProfileRepository extends JpaRepository<GuardrailSpendingProfileEntity, UUID> {

    Optional<GuardrailSpendingProfileEntity> findByTenant_IdAndScenario_Id(UUID tenantId, UUID scenarioId);

    Optional<GuardrailSpendingProfileEntity> findByScenario_Id(UUID scenarioId);
}
