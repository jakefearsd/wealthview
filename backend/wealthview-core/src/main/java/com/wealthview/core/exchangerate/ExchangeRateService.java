package com.wealthview.core.exchangerate;

import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.core.exchangerate.dto.ExchangeRateResponse;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ExchangeRateRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.wealthview.core.common.Money;

@Service
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private final ExchangeRateRepository exchangeRateRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository,
                               TenantRepository tenantRepository,
                               AccountRepository accountRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
    }

    @CacheEvict(value = {"exchangeRates", "accountBalances"}, key = "#tenantId")
    @Transactional
    public ExchangeRateResponse create(UUID tenantId, ExchangeRateRequest request) {
        if ("USD".equals(request.currencyCode())) {
            throw new IllegalArgumentException("Cannot create exchange rate for USD — it is always 1.0");
        }

        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        var existing = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, request.currencyCode());
        if (existing.isPresent()) {
            throw new DuplicateEntityException(
                    "Exchange rate for " + request.currencyCode() + " already exists");
        }

        var entity = new ExchangeRateEntity(tenant, request.currencyCode(), request.rateToUsd());
        entity = exchangeRateRepository.save(entity);
        log.info("Exchange rate created: {} = {} USD for tenant {}", request.currencyCode(),
                request.rateToUsd(), tenantId);
        return ExchangeRateResponse.from(entity);
    }

    @CacheEvict(value = {"exchangeRates", "accountBalances"}, key = "#tenantId")
    @Transactional
    public ExchangeRateResponse update(UUID tenantId, String currencyCode, BigDecimal rateToUsd) {
        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currencyCode)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Exchange rate not found for currency: " + currencyCode));

        entity.setRateToUsd(rateToUsd);
        entity.setUpdatedAt(OffsetDateTime.now());
        entity = exchangeRateRepository.save(entity);
        log.info("Exchange rate updated: {} = {} USD for tenant {}", currencyCode, rateToUsd, tenantId);
        return ExchangeRateResponse.from(entity);
    }

    @CacheEvict(value = {"exchangeRates", "accountBalances"}, key = "#tenantId")
    @Transactional
    public void delete(UUID tenantId, String currencyCode) {
        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currencyCode)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Exchange rate not found for currency: " + currencyCode));

        var accountsUsingCurrency = accountRepository.findByTenant_Id(tenantId).stream()
                .filter(a -> currencyCode.equals(a.getCurrency()))
                .count();

        if (accountsUsingCurrency > 0) {
            throw new IllegalStateException("Cannot delete " + currencyCode + " rate: "
                    + accountsUsingCurrency + " account(s) use this currency");
        }

        exchangeRateRepository.delete(entity);
        log.info("Exchange rate deleted: {} for tenant {}", currencyCode, tenantId);
    }

    @Cacheable(value = "exchangeRates", key = "#tenantId")
    @Transactional(readOnly = true)
    public List<ExchangeRateResponse> list(UUID tenantId) {
        return exchangeRateRepository.findByTenant_Id(tenantId).stream()
                .map(ExchangeRateResponse::from)
                .toList();
    }

    public BigDecimal convertToUsd(BigDecimal amount, String currency, UUID tenantId) {
        if ("USD".equals(currency)) {
            return amount;
        }

        var entity = exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, currency)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No exchange rate found for " + currency
                                + " — add one in Settings before using this currency"));

        return amount.multiply(entity.getRateToUsd()).setScale(Money.SCALE, Money.ROUNDING);
    }
}
