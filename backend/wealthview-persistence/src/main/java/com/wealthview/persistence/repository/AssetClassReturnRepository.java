package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.AssetClassReturnEntity;

public interface AssetClassReturnRepository extends JpaRepository<AssetClassReturnEntity, UUID> {

    List<AssetClassReturnEntity> findAllByOrderByYearAscAssetClassAsc();
}
