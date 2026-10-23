package com.wealthview.persistence.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.BondMaturityReturnEntity;

public interface BondMaturityReturnRepository extends JpaRepository<BondMaturityReturnEntity, UUID> {

    List<BondMaturityReturnEntity> findAllByOrderByYearAscMaturityAsc();
}
