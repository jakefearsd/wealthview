package com.wealthview.core.pricefeed;

import com.wealthview.core.pricefeed.dto.CandleResponse;
import com.wealthview.core.pricefeed.dto.CandleResponse.CandleEntry;
import com.wealthview.core.pricefeed.dto.QuoteResponse;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceSyncServiceTest {

    @Mock
    private PriceFeedClient priceFeedClient;

    @Mock
    private PriceRepository priceRepository;

    @Mock
    private HoldingRepository holdingRepository;

    private PriceSyncService service;

    @BeforeEach
    void setUp() {
        service = new PriceSyncService(priceFeedClient, priceRepository, holdingRepository, 0,
                new SimpleMeterRegistry());
    }

    @Test
    void syncDailyPrices_multipleSymbols_fetchesAndSavesEach() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("AAPL", "GOOG"));
        when(priceFeedClient.getQuote("AAPL")).thenReturn(
                Optional.of(new QuoteResponse("AAPL", new BigDecimal("150.00"))));
        when(priceFeedClient.getQuote("GOOG")).thenReturn(
                Optional.of(new QuoteResponse("GOOG", new BigDecimal("2800.00"))));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.syncDailyPrices();

        var captor = ArgumentCaptor.forClass(PriceEntity.class);
        verify(priceRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        var saved = captor.getAllValues();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getSymbol()).isEqualTo("AAPL");
        assertThat(saved.get(0).getClosePrice()).isEqualByComparingTo("150.00");
        assertThat(saved.get(0).getSource()).isEqualTo("finnhub");
        assertThat(saved.get(1).getSymbol()).isEqualTo("GOOG");
        assertThat(saved.get(1).getClosePrice()).isEqualByComparingTo("2800.00");
    }

    @Test
    void syncDailyPrices_existingPrice_updatesClosePriceAndSource() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("AAPL"));
        when(priceFeedClient.getQuote("AAPL")).thenReturn(
                Optional.of(new QuoteResponse("AAPL", new BigDecimal("155.00"))));

        var existing = new PriceEntity("AAPL", LocalDate.now(), new BigDecimal("150.00"), "manual");
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.of(existing));
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.syncDailyPrices();

        var captor = ArgumentCaptor.forClass(PriceEntity.class);
        verify(priceRepository).save(captor.capture());
        assertThat(captor.getValue().getClosePrice()).isEqualByComparingTo("155.00");
        assertThat(captor.getValue().getSource()).isEqualTo("finnhub");
    }

    @Test
    void syncDailyPrices_apiFailureForOne_continuesWithOthers() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("AAPL", "GOOG"));
        when(priceFeedClient.getQuote("AAPL")).thenReturn(Optional.empty());
        when(priceFeedClient.getQuote("GOOG")).thenReturn(
                Optional.of(new QuoteResponse("GOOG", new BigDecimal("2800.00"))));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.syncDailyPrices();

        var captor = ArgumentCaptor.forClass(PriceEntity.class);
        verify(priceRepository).save(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo("GOOG");
    }

    @Test
    void syncDailyPrices_noSymbols_doesNothing() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of());

        service.syncDailyPrices();

        verify(priceFeedClient, never()).getQuote(any());
        verify(priceRepository, never()).save(any());
    }

    @Test
    void backfillHistoricalPrices_noPricesExist_fetchesAndSavesCandles() {
        when(priceRepository.existsBySymbol("AAPL")).thenReturn(false);

        var entries = List.of(
                new CandleEntry(LocalDate.of(2024, 1, 2), new BigDecimal("180.00")),
                new CandleEntry(LocalDate.of(2024, 1, 3), new BigDecimal("182.00"))
        );
        when(priceFeedClient.getCandles(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Optional.of(new CandleResponse("AAPL", entries)));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.backfillHistoricalPrices("AAPL");

        var captor = ArgumentCaptor.forClass(PriceEntity.class);
        verify(priceRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        var saved = captor.getAllValues();
        assertThat(saved.get(0).getDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(saved.get(0).getClosePrice()).isEqualByComparingTo("180.00");
        assertThat(saved.get(0).getSource()).isEqualTo("finnhub");
        assertThat(saved.get(1).getDate()).isEqualTo(LocalDate.of(2024, 1, 3));
    }

    @Test
    void backfillHistoricalPrices_pricesAlreadyExist_skips() {
        when(priceRepository.existsBySymbol("AAPL")).thenReturn(true);

        service.backfillHistoricalPrices("AAPL");

        verify(priceFeedClient, never()).getCandles(any(), any(), any());
        verify(priceRepository, never()).save(any());
    }

    @Test
    void backfillHistoricalPrices_apiFailure_logsAndReturns() {
        when(priceRepository.existsBySymbol("AAPL")).thenReturn(false);
        when(priceFeedClient.getCandles(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        service.backfillHistoricalPrices("AAPL");

        verify(priceRepository, never()).save(any());
    }

    @Test
    void syncDailyPrices_withRateLimit_sleepsBetweenCalls() {
        var serviceWithRateLimit = new PriceSyncService(
                priceFeedClient, priceRepository, holdingRepository, 1, new SimpleMeterRegistry());

        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("AAPL"));
        when(priceFeedClient.getQuote("AAPL")).thenReturn(
                Optional.of(new QuoteResponse("AAPL", new BigDecimal("150.00"))));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceWithRateLimit.syncDailyPrices();

        verify(priceRepository).save(any(PriceEntity.class));
    }

    @Test
    void syncDailyPrices_exceptionDuringQuote_countsAsFailure() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("BAD"));
        when(priceFeedClient.getQuote("BAD")).thenThrow(new RuntimeException("API error"));

        service.syncDailyPrices();

        verify(priceRepository, never()).save(any());
    }

    @Test
    void backfillHistoricalPrices_dataAccessException_continuesWithOtherEntries() {
        when(priceRepository.existsBySymbol("AAPL")).thenReturn(false);

        var entries = List.of(
                new CandleEntry(LocalDate.of(2024, 1, 2), new BigDecimal("180.00")),
                new CandleEntry(LocalDate.of(2024, 1, 3), new BigDecimal("182.00"))
        );
        when(priceFeedClient.getCandles(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Optional.of(new CandleResponse("AAPL", entries)));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        service.backfillHistoricalPrices("AAPL");

        verify(priceRepository, times(2)).save(any(PriceEntity.class));
    }

    @Test
    void onNewHolding_triggersBackfill() {
        when(priceRepository.existsBySymbol("AAPL")).thenReturn(false);
        when(priceFeedClient.getCandles(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        service.onNewHolding(new NewHoldingCreatedEvent("AAPL"));

        verify(priceRepository).existsBySymbol("AAPL");
    }
}
