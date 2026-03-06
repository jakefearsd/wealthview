package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.SpendingProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpendingProfileRepository extends JpaRepository<SpendingProfileEntity, UUID> {

    List<SpendingProfileEntity> findByTenant_IdOrderByCreatedAtDesc(UUID tenantId);

    Optional<SpendingProfileEntity> findByTenant_IdAndId(UUID tenantId, UUID id);
}
