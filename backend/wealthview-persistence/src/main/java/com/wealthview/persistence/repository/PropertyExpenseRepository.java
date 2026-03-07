package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PropertyExpenseRepository extends JpaRepository<PropertyExpenseEntity, UUID> {

    List<PropertyExpenseEntity> findByProperty_IdAndDateBetween(UUID propertyId, LocalDate from, LocalDate to);

    List<PropertyExpenseEntity> findByProperty_IdAndDateBetweenAndCategoryNot(
            UUID propertyId, LocalDate from, LocalDate to, String category);
}
