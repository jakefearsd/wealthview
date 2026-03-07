package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    Page<AuditLogEntity> findByTenantIdAndEntityTypeOrderByCreatedAtDesc(UUID tenantId, String entityType, Pageable pageable);
}
