package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyExpenseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyExpenseRepository extends JpaRepository<PropertyExpenseEntity, UUID> {

    Optional<PropertyExpenseEntity> findByTenant_IdAndId(UUID tenantId, UUID id);

    List<PropertyExpenseEntity> findByTenant_IdAndProperty_Id(UUID tenantId, UUID propertyId);

    List<PropertyExpenseEntity> findByProperty_IdAndDateBetween(UUID propertyId, LocalDate from, LocalDate to);

    List<PropertyExpenseEntity> findByProperty_IdAndDateBetweenAndCategoryNot(
            UUID propertyId, LocalDate from, LocalDate to, String category);

    @Query("""
            SELECT e FROM PropertyExpenseEntity e
            WHERE e.property.id = :propertyId
            AND (
                (e.frequency = 'monthly' AND e.date BETWEEN :from AND :to)
                OR (e.frequency = 'annual' AND e.date BETWEEN :annualFrom AND :to)
            )
            """)
    List<PropertyExpenseEntity> findOverlapping(
            @Param("propertyId") UUID propertyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("annualFrom") LocalDate annualFrom);

    @Query("""
            SELECT e FROM PropertyExpenseEntity e
            WHERE e.property.id = :propertyId
            AND e.category <> :excludeCategory
            AND (
                (e.frequency = 'monthly' AND e.date BETWEEN :from AND :to)
                OR (e.frequency = 'annual' AND e.date BETWEEN :annualFrom AND :to)
            )
            """)
    List<PropertyExpenseEntity> findOverlappingExcludingCategory(
            @Param("propertyId") UUID propertyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("annualFrom") LocalDate annualFrom,
            @Param("excludeCategory") String excludeCategory);
}
