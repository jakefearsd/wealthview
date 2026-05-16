package com.wealthview.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.wealthview.persistence.entity.SystemConfigEntity;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfigEntity, String> {
}
