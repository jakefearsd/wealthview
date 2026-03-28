package com.wealthview.core.exchangerate;

import com.wealthview.core.exception.DuplicateEntityException;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exchangerate.dto.ExchangeRateRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ExchangeRateRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock private ExchangeRateRepository exchangeRateRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AccountRepository accountRepository;
    @InjectMocks private ExchangeRateService exchangeRateService;

    private TenantEntity tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void create_validRequest_returnsResponse() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.empty());
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = exchangeRateService.create(tenantId,
                new ExchangeRateRequest("EUR", new BigDecimal("1.08")));

        assertThat(result.currencyCode()).isEqualTo("EUR");
        assertThat(result.rateToUsd()).isEqualByComparingTo(new BigDecimal("1.08"));
    }

    @Test
    void create_duplicateCurrency_throwsDuplicateEntity() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));

        assertThatThrownBy(() -> exchangeRateService.create(tenantId,
                new ExchangeRateRequest("EUR", new BigDecimal("1.10"))))
                .isInstanceOf(DuplicateEntityException.class);
    }

    @Test
    void create_usdCurrency_throwsIllegalArgument() {
        assertThatThrownBy(() -> exchangeRateService.create(tenantId,
                new ExchangeRateRequest("USD", BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_existingRate_updatesValue() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = exchangeRateService.update(tenantId, "EUR", new BigDecimal("1.12"));

        assertThat(result.rateToUsd()).isEqualByComparingTo(new BigDecimal("1.12"));
    }

    @Test
    void update_nonExistentCurrency_throwsNotFound() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "GBP"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.update(tenantId, "GBP", new BigDecimal("1.25")))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void delete_noAccountsUsingCurrency_succeeds() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of());

        exchangeRateService.delete(tenantId, "EUR");

        verify(exchangeRateRepository).delete(entity);
    }

    @Test
    void delete_accountsUsingCurrency_throwsIllegalState() {
        var entity = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(entity));
        var account = new AccountEntity(tenant, "Euro Account", "brokerage", null, "EUR");
        when(accountRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(account));

        assertThatThrownBy(() -> exchangeRateService.delete(tenantId, "EUR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 account");
    }

    @Test
    void list_returnsAllRatesForTenant() {
        var eur = new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"));
        var gbp = new ExchangeRateEntity(tenant, "GBP", new BigDecimal("1.27"));
        when(exchangeRateRepository.findByTenant_Id(tenantId))
                .thenReturn(List.of(eur, gbp));

        var result = exchangeRateService.list(tenantId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting("currencyCode").containsExactlyInAnyOrder("EUR", "GBP");
    }

    @Test
    void convertToUsd_usdCurrency_returnsAmountUnchanged() {
        var result = exchangeRateService.convertToUsd(new BigDecimal("100.00"), "USD", tenantId);
        assertThat(result).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void convertToUsd_eurCurrency_multipliesByRate() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(tenant, "EUR", new BigDecimal("1.08"))));

        var result = exchangeRateService.convertToUsd(new BigDecimal("1000.00"), "EUR", tenantId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("1080.00"));
    }

    @Test
    void convertToUsd_missingRate_throwsEntityNotFound() {
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "JPY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> exchangeRateService.convertToUsd(
                new BigDecimal("10000"), "JPY", tenantId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("JPY");
    }
}
