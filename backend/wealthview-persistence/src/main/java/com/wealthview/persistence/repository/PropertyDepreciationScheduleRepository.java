package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyDepreciationScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyDepreciationScheduleRepository extends JpaRepository<PropertyDepreciationScheduleEntity, UUID> {

    List<PropertyDepreciationScheduleEntity> findByProperty_IdOrderByTaxYear(UUID propertyId);

    Optional<PropertyDepreciationScheduleEntity> findByProperty_IdAndTaxYear(UUID propertyId, int taxYear);

    void deleteByProperty_Id(UUID propertyId);
}
