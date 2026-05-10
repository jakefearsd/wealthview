package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.StockSplitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockSplitRepository extends JpaRepository<StockSplitEntity, java.util.UUID> {

    Optional<StockSplitEntity> findBySymbolAndEffectiveDate(String symbol, LocalDate effectiveDate);

    boolean existsBySymbolAndEffectiveDate(String symbol, LocalDate effectiveDate);

    List<StockSplitEntity> findBySymbolOrderByEffectiveDateAsc(String symbol);

    @Query("""
            SELECT s FROM StockSplitEntity s
            WHERE s.symbol IN :symbols
              AND s.effectiveDate >= :from
              AND s.effectiveDate <= :to
            ORDER BY s.effectiveDate DESC
            """)
    List<StockSplitEntity> findBySymbolsInAndDateRange(@Param("symbols") List<String> symbols,
                                                      @Param("from") LocalDate from,
                                                      @Param("to") LocalDate to);
}
