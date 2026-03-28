package com.wealthview.persistence.repository;

import com.wealthview.persistence.entity.ExchangeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRateEntity, UUID> {
    Optional<ExchangeRateEntity> findByTenant_IdAndCurrencyCode(UUID tenantId, String currencyCode);
    List<ExchangeRateEntity> findByTenant_Id(UUID tenantId);
}
