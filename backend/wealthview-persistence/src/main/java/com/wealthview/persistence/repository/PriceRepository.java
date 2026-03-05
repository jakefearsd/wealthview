package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PriceRepository extends JpaRepository<PriceEntity, PriceId> {

    Optional<PriceEntity> findFirstBySymbolOrderByDateDesc(String symbol);

    boolean existsBySymbol(String symbol);
}
