package com.wealthview.persistence.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.wealthview.persistence.entity.LoginActivityEntity;

@Repository
public interface LoginActivityRepository extends JpaRepository<LoginActivityEntity, UUID> {

    List<LoginActivityEntity> findTop50ByOrderByCreatedAtDesc();

    List<LoginActivityEntity> findTop50ByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    long countBySuccessTrueAndCreatedAtAfter(OffsetDateTime since);
}
