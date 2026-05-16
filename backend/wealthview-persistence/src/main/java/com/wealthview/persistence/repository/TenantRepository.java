package com.wealthview.persistence.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.TenantEntity;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {
}
