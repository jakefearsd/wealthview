package com.wealthview.core.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.repository.PriceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LatestPriceLookupTest {

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private LatestPriceLookup latestPriceLookup;

    @Test
    void latestFor_knownSymbols_mapsSymbolToClosePrice() {
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        var goog = new PriceEntity("GOOG", LocalDate.of(2025, 3, 1), new BigDecimal("150.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL", "GOOG")))
                .thenReturn(List.of(aapl, goog));

        var result = latestPriceLookup.latestFor(List.of("AAPL", "GOOG"));

        assertThat(result)
                .hasSize(2)
                .containsEntry("AAPL", new BigDecimal("200.00"))
                .containsEntry("GOOG", new BigDecimal("150.00"));
    }

    @Test
    void latestFor_symbolMissingFromRepository_omittedFromResultMap() {
        // Repository returns a row for AAPL only; XYZ has no price data (e.g. an unpriced
        // money-market symbol) and must simply be absent from the result, not null-mapped.
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL", "XYZ")))
                .thenReturn(List.of(aapl));

        var result = latestPriceLookup.latestFor(List.of("AAPL", "XYZ"));

        assertThat(result).containsOnlyKeys("AAPL");
    }

    @Test
    void latestFor_emptySymbolCollection_returnsEmptyMapWithoutCallingRepository() {
        var result = latestPriceLookup.latestFor(List.of());

        assertThat(result).isEmpty();
        verify(priceRepository, org.mockito.Mockito.never()).findLatestBySymbolIn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void latestFor_duplicateSymbolsInInput_deduplicatesBeforeQuerying() {
        var aapl = new PriceEntity("AAPL", LocalDate.of(2025, 3, 1), new BigDecimal("200.00"), "manual");
        when(priceRepository.findLatestBySymbolIn(List.of("AAPL")))
                .thenReturn(List.of(aapl));

        var result = latestPriceLookup.latestFor(List.of("AAPL", "AAPL"));

        assertThat(result).containsEntry("AAPL", new BigDecimal("200.00"));
        verify(priceRepository).findLatestBySymbolIn(List.of("AAPL"));
    }
}
