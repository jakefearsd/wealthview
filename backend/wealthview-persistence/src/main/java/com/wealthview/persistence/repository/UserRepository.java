package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    List<UserEntity> findByTenantId(UUID tenantId);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
