package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
}
