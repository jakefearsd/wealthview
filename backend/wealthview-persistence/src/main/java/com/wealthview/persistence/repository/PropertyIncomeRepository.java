package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyIncomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PropertyIncomeRepository extends JpaRepository<PropertyIncomeEntity, UUID> {

    List<PropertyIncomeEntity> findByProperty_IdAndDateBetween(UUID propertyId, LocalDate from, LocalDate to);
}
