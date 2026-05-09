package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.MobileAppVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MobileAppVersionRepository extends JpaRepository<MobileAppVersionEntity, String> {
}
