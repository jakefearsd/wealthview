package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    @Query("SELECT u FROM UserEntity u JOIN FETCH u.tenant")
    List<UserEntity> findAllWithTenant();

    List<UserEntity> findByTenant_Id(UUID tenantId);

    boolean existsByEmail(String email);

    Optional<UserEntity> findByTenant_IdAndId(UUID tenantId, UUID id);

    long countByTenant_Id(UUID tenantId);
}
