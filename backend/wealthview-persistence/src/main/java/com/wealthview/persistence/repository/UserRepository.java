package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    List<UserEntity> findByTenant_Id(UUID tenantId);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByTenant_IdAndId(UUID tenantId, UUID id);
}
