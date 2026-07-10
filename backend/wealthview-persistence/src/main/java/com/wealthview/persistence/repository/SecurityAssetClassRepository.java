package com.wealthview.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.SecurityAssetClassEntity;

public interface SecurityAssetClassRepository extends JpaRepository<SecurityAssetClassEntity, UUID> {

    Optional<SecurityAssetClassEntity> findBySymbol(String symbol);
}
