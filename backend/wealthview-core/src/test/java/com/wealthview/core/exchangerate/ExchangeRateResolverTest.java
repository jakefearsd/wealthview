package com.wealthview.core.exchangerate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.persistence.entity.ExchangeRateEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.ExchangeRateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeRateResolverTest {

    private final ExchangeRateRepository exchangeRateRepository = mock(ExchangeRateRepository.class);
    private final ExchangeRateResolver resolver = new ExchangeRateResolver(exchangeRateRepository);

    @Test
    void resolveRateToUsd_knownCurrency_returnsStoredRate() {
        var tenantId = UUID.randomUUID();
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "EUR"))
                .thenReturn(Optional.of(new ExchangeRateEntity(new TenantEntity("Test"), "EUR",
                        new BigDecimal("1.08"))));

        var rate = resolver.resolveRateToUsd(tenantId, "EUR");

        assertThat(rate).isEqualByComparingTo("1.08");
    }

    @Test
    void resolveRateToUsd_unknownCurrency_pointsTheUserAtTheAdminExchangeRatesScreen() {
        var tenantId = UUID.randomUUID();
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "JPY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveRateToUsd(tenantId, "JPY"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No exchange rate found for JPY "
                        + "— add one under Admin → Exchange Rates before using this currency");
    }

    @Test
    void resolveRateToUsd_unknownCurrency_doesNotMentionTheRetiredSettingsPage() {
        var tenantId = UUID.randomUUID();
        when(exchangeRateRepository.findByTenant_IdAndCurrencyCode(tenantId, "JPY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveRateToUsd(tenantId, "JPY"))
                .hasMessageNotContaining("Settings");
    }
}
