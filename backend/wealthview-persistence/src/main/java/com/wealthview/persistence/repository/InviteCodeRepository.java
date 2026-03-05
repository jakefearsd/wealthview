package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.InviteCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteCodeRepository extends JpaRepository<InviteCodeEntity, UUID> {

    Optional<InviteCodeEntity> findByCode(String code);

    List<InviteCodeEntity> findByTenantId(UUID tenantId);
}
