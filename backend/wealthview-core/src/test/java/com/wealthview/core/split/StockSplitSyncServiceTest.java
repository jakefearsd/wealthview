package com.wealthview.core.split;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.dto.DetectedSplit;
import com.wealthview.persistence.repository.StockSplitRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSplitSyncServiceTest {

    @Mock
    private SplitDetectionClient splitDetectionClient;

    @Mock
    private StockSplitService stockSplitService;

    @Mock
    private StockSplitRepository stockSplitRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private SystemConfigService systemConfigService;

    private StockSplitSyncService service;

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        service = new StockSplitSyncService(splitDetectionClient, stockSplitService,
                stockSplitRepository, transactionRepository, systemConfigService,
                new SimpleMeterRegistry());

        appender = new ListAppender<>();
        appender.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(StockSplitSyncService.class);
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    void syncAll_newSplit_appliesItAndCountsAppliedAndDiscovered() {
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get(any())).thenReturn(null);
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);

        var result = service.syncAll();

        assertThat(result.symbolsScanned()).isEqualTo(1);
        assertThat(result.splitsDiscovered()).isEqualTo(1);
        assertThat(result.splitsApplied()).isEqualTo(1);
        assertThat(result.failedSymbols()).isEmpty();
        verify(stockSplitService).applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub");
    }

    @Test
    void syncAll_splitAlreadyRecorded_discoveredButNotApplied() {
        // A split that already exists is counted as discovered but must NOT be
        // re-applied — pins the negated existsBySymbolAndEffectiveDate guard.
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get(any())).thenReturn(null);
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(true);

        var result = service.syncAll();

        assertThat(result.splitsDiscovered()).isEqualTo(1);
        assertThat(result.splitsApplied()).isEqualTo(0);
        verify(stockSplitService, never()).applySplit(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void syncAll_fetchThrows_recordsSymbolAsFailedAndContinues() {
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants())
                .thenReturn(List.of("AAPL", "MSFT"));
        when(systemConfigService.get(any())).thenReturn(null);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any()))
                .thenThrow(new RuntimeException("network down"));
        when(splitDetectionClient.fetch(eq("MSFT"), any(), any())).thenReturn(List.of());

        var result = service.syncAll();

        assertThat(result.symbolsScanned()).isEqualTo(2);
        assertThat(result.failedSymbols()).containsExactly("AAPL");
    }

    @Test
    void syncAll_persistsLastSyncTimestamp() {
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of());
        when(systemConfigService.get(any())).thenReturn(null);

        service.syncAll();

        verify(systemConfigService).set(eq("stock_splits.last_sync_at"), any());
    }

    @Test
    void syncAll_noStoredLastSync_fetchesWindowStartingSevenDaysAgo() {
        // computeFromDate: with no stored timestamp the window must start
        // exactly 7 days before today.
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get("stock_splits.last_sync_at")).thenReturn(null);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of());

        service.syncAll();

        verify(splitDetectionClient).fetch("AAPL",
                LocalDate.now().minusDays(7), LocalDate.now());
    }

    @Test
    void syncAll_withStoredLastSync_fetchesWindowSevenDaysBeforeStoredDate() {
        // computeFromDate: window start = storedSyncDate - 7 days (overlap).
        var stored = Instant.parse("2024-06-20T12:00:00Z");
        var expectedFrom = stored.atZone(ZoneId.systemDefault()).toLocalDate().minusDays(7);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get("stock_splits.last_sync_at")).thenReturn(stored.toString());
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of());

        service.syncAll();

        verify(splitDetectionClient).fetch("AAPL", expectedFrom, LocalDate.now());
    }

    @Test
    void syncAll_unparseableStoredLastSync_fallsBackToSevenDaysAgo() {
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get("stock_splits.last_sync_at")).thenReturn("not-a-timestamp");
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of());

        service.syncAll();

        verify(splitDetectionClient).fetch("AAPL",
                LocalDate.now().minusDays(7), LocalDate.now());
    }

    @Test
    void syncAll_appliedSplitAffectsNoTenants_warnsInsteadOfSilentlySucceeding() {
        // AAPL only reaches applySplit because findDistinctSymbolsAcrossAllTenants()
        // just reported at least one tenant holds it — so a post-apply read of
        // findDistinctTenantIdsBySymbol coming back empty for that exact symbol is
        // never legitimate for a sync-discovered split. It must be surfaced loudly,
        // not swallowed into "1 applied" with zero trace.
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get(any())).thenReturn(null);
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        service.syncAll();

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("AAPL")
                        && event.getFormattedMessage().contains("ZERO tenants"));
    }

    @Test
    void syncAll_appliedSplitAffectsTenants_doesNotWarn() {
        // Sanity counterpart: the normal, non-anomalous path must stay silent at WARN.
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(systemConfigService.get(any())).thenReturn(null);
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of(UUID.randomUUID()));

        service.syncAll();

        assertThat(appender.list).isEmpty();
    }
}
