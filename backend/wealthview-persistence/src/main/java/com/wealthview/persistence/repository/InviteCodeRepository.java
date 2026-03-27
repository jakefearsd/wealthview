package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.InviteCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteCodeRepository extends JpaRepository<InviteCodeEntity, UUID> {

    Optional<InviteCodeEntity> findByCode(String code);

    List<InviteCodeEntity> findByTenant_Id(UUID tenantId);

    @Query("SELECT ic FROM InviteCodeEntity ic LEFT JOIN FETCH ic.createdBy LEFT JOIN FETCH ic.consumedBy WHERE ic.tenant.id = :tenantId")
    List<InviteCodeEntity> findByTenantIdWithUsers(UUID tenantId);

    @Modifying
    @Transactional
    int deleteByTenant_IdAndConsumedByIsNotNull(UUID tenantId);
}
