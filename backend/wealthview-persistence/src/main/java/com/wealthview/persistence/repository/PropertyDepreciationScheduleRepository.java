package com.wealthview.persistence.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.wealthview.persistence.entity.PropertyDepreciationScheduleEntity;

public interface PropertyDepreciationScheduleRepository
        extends JpaRepository<PropertyDepreciationScheduleEntity, UUID> {

    List<PropertyDepreciationScheduleEntity> findByProperty_IdOrderByTaxYear(UUID propertyId);

    Optional<PropertyDepreciationScheduleEntity> findByProperty_IdAndTaxYear(UUID propertyId, int taxYear);

    void deleteByProperty_Id(UUID propertyId);
}
