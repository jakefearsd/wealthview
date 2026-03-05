package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyRepository extends JpaRepository<PropertyEntity, UUID> {

    List<PropertyEntity> findByTenant_Id(UUID tenantId);

    Optional<PropertyEntity> findByTenant_IdAndId(UUID tenantId, UUID id);
}
