package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PropertyIncomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PropertyIncomeRepository extends JpaRepository<PropertyIncomeEntity, UUID> {

    List<PropertyIncomeEntity> findByProperty_IdAndDateBetween(UUID propertyId, LocalDate from, LocalDate to);

    @Query("""
            SELECT i FROM PropertyIncomeEntity i
            WHERE i.property.id = :propertyId
            AND (
                (i.frequency = 'monthly' AND i.date BETWEEN :from AND :to)
                OR (i.frequency = 'annual' AND i.date BETWEEN :annualFrom AND :to)
            )
            """)
    List<PropertyIncomeEntity> findOverlapping(
            @Param("propertyId") UUID propertyId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("annualFrom") LocalDate annualFrom);
}
