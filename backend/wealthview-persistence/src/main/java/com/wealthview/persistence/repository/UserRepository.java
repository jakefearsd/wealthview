package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;

import com.wealthview.persistence.entity.UserEntity;

public interface UserRepository extends TenantScopedRepository<UserEntity> {

    Optional<UserEntity> findByEmail(String email);

    @Query("SELECT u FROM UserEntity u JOIN FETCH u.tenant")
    List<UserEntity> findAllWithTenant();

    boolean existsByEmail(String email);

    long countByTenant_Id(UUID tenantId);
}
