package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ImportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImportJobRepository extends JpaRepository<ImportJobEntity, UUID> {

    List<ImportJobEntity> findByTenantId(UUID tenantId);
}
