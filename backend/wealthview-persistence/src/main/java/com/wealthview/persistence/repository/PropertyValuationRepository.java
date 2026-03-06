package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyValuationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyValuationRepository extends JpaRepository<PropertyValuationEntity, UUID> {

    List<PropertyValuationEntity> findByProperty_IdAndTenant_IdOrderByValuationDateDesc(
            UUID propertyId, UUID tenantId);

    Optional<PropertyValuationEntity> findByProperty_IdAndSourceAndValuationDate(
            UUID propertyId, String source, LocalDate valuationDate);
}
