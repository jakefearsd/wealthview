package com.wealthview.persistence.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;

public interface PriceRepository extends JpaRepository<PriceEntity, PriceId> {

    Optional<PriceEntity> findFirstBySymbolOrderByDateDesc(String symbol);

    boolean existsBySymbol(String symbol);

    List<PriceEntity> findBySymbolInAndDateBetweenOrderBySymbolAscDateAsc(
            List<String> symbols, LocalDate start, LocalDate end);

    @Query("""
            SELECT p FROM PriceEntity p
            WHERE p.symbol IN :symbols
            AND p.date = (SELECT MAX(p2.date) FROM PriceEntity p2 WHERE p2.symbol = p.symbol)
            """)
    List<PriceEntity> findLatestBySymbolIn(@Param("symbols") List<String> symbols);

    @Query(value = "SELECT DISTINCT ON (symbol) * FROM prices ORDER BY symbol, date DESC", nativeQuery = true)
    List<PriceEntity> findLatestPerSymbol();

    List<PriceEntity> findBySymbolAndDateBetweenOrderByDateDesc(String symbol, LocalDate from, LocalDate to);

    @Modifying
    @Transactional
    @Query("DELETE FROM PriceEntity p WHERE p.symbol = :symbol AND p.date = :date")
    void deleteBySymbolAndDate(@Param("symbol") String symbol, @Param("date") LocalDate date);

    List<PriceEntity> findBySymbolAndDateBefore(String symbol, LocalDate date);
}
