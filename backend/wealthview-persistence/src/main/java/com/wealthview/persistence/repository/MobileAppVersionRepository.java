package com.wealthview.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.wealthview.persistence.entity.MobileAppVersionEntity;

@Repository
public interface MobileAppVersionRepository extends JpaRepository<MobileAppVersionEntity, String> {
}
