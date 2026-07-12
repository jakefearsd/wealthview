package com.wealthview.core.split;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockSplitBackfillRunnerTest {

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

    private StockSplitBackfillRunner runner;

    private ListAppender<ILoggingEvent> appender;
    private Logger runnerLogger;

    @BeforeEach
    void setUp() {
        runner = newRunner(true);

        appender = new ListAppender<>();
        appender.start();
        runnerLogger = (Logger) LoggerFactory.getLogger(StockSplitBackfillRunner.class);
        runnerLogger.addAppender(appender);
        runnerLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        runnerLogger.detachAppender(appender);
    }

    private StockSplitBackfillRunner newRunner(boolean backfillAutoRun) {
        return new StockSplitBackfillRunner(splitDetectionClient, stockSplitService,
                stockSplitRepository, transactionRepository, systemConfigService,
                new SimpleMeterRegistry(), backfillAutoRun);
    }

    @Test
    void onApplicationReady_autoRunDisabled_doesNotScheduleBackfill() {
        // The gate must short-circuit BEFORE anything is handed to the executor:
        // no flag read, no symbol scan — the startup path is completely inert.
        runner = newRunner(false);

        runner.onApplicationReady();

        verifyNoInteractions(systemConfigService, transactionRepository,
                splitDetectionClient, stockSplitService);
    }

    @Test
    void onApplicationReady_autoRunEnabled_runsBackfillAsynchronously() {
        // Default-true production behavior: the event listener hands runIfNeeded
        // to the background executor. The flag read is the async run's first
        // action, so a timeout-verify on it proves the run was scheduled.
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn("true");

        runner.onApplicationReady();

        verify(systemConfigService, timeout(5000)).get("stock_splits.backfill_completed");
    }

    @Test
    void constructor_autoRunProperty_isAppStockSplitsBackfillAutoRunDefaultTrue() {
        // Pins the property binding so the prod path cannot silently lose the
        // auto-run: the @Value expression must keep matchIfMissing-equivalent
        // semantics (default true when the property is absent).
        var ctor = StockSplitBackfillRunner.class.getDeclaredConstructors()[0];
        var params = ctor.getParameters();
        var valueAnnotation = params[params.length - 1].getAnnotation(Value.class);

        assertThat(valueAnnotation).isNotNull();
        assertThat(valueAnnotation.value()).isEqualTo("${app.stock-splits.backfill-auto-run:true}");
    }

    @Test
    void runIfNeeded_whenAlreadyCompleted_doesNothing() {
        // The backfill flag gates the whole run — when set, the runner must not
        // scan symbols nor mark the flag again.
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn("true");

        runner.runIfNeeded();

        verify(transactionRepository, never()).findDistinctSymbolsAcrossAllTenants();
        verify(systemConfigService, never()).set(any(), any());
    }

    @Test
    void runIfNeeded_completedFlagCaseInsensitive_stillSkips() {
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn("TRUE");

        runner.runIfNeeded();

        verify(transactionRepository, never()).findDistinctSymbolsAcrossAllTenants();
    }

    @Test
    void runIfNeeded_firstRun_appliesNewSplitsAndSetsCompletedFlag() {
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn(null);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(transactionRepository.findEarliestDateBySymbol("AAPL"))
                .thenReturn(Optional.of(LocalDate.of(2015, 1, 1)));
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);

        runner.runIfNeeded();

        verify(stockSplitService).applySplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "backfill");
        verify(systemConfigService).set("stock_splits.backfill_completed", "true");
    }

    @Test
    void runIfNeeded_symbolWithNoEarliestDate_isSkipped() {
        // A symbol with no earliest transaction date must be skipped (continue),
        // not passed to the client with a null from-date.
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn(null);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(transactionRepository.findEarliestDateBySymbol("AAPL")).thenReturn(Optional.empty());

        runner.runIfNeeded();

        verify(splitDetectionClient, never()).fetch(any(), any(), any());
        verify(systemConfigService).set("stock_splits.backfill_completed", "true");
    }

    @Test
    void runIfNeeded_existingSplit_isNotReapplied() {
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn(null);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(transactionRepository.findEarliestDateBySymbol("AAPL"))
                .thenReturn(Optional.of(LocalDate.of(2015, 1, 1)));
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(true);

        runner.runIfNeeded();

        verify(stockSplitService, never()).applySplit(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void runIfNeeded_appliedSplitAffectsNoTenants_warnsInsteadOfSilentlySucceeding() {
        // AAPL only reaches applySplit because findDistinctSymbolsAcrossAllTenants()
        // just reported at least one tenant holds it — so a post-apply read of
        // findDistinctTenantIdsBySymbol coming back empty for that exact symbol is
        // never legitimate for a backfill-discovered split (unlike an admin-driven
        // apply for a symbol nobody currently holds). It must be surfaced loudly,
        // not swallowed into "1 splits applied" with zero trace.
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn(null);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(transactionRepository.findEarliestDateBySymbol("AAPL"))
                .thenReturn(Optional.of(LocalDate.of(2015, 1, 1)));
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL")).thenReturn(List.of());

        runner.runIfNeeded();

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("AAPL")
                        && event.getFormattedMessage().contains("ZERO tenants"));
    }

    @Test
    void runIfNeeded_appliedSplitAffectsTenants_doesNotWarn() {
        // Sanity counterpart: the normal, non-anomalous path must stay silent at WARN.
        when(systemConfigService.get("stock_splits.backfill_completed")).thenReturn(null);
        when(transactionRepository.findDistinctSymbolsAcrossAllTenants()).thenReturn(List.of("AAPL"));
        when(transactionRepository.findEarliestDateBySymbol("AAPL"))
                .thenReturn(Optional.of(LocalDate.of(2015, 1, 1)));
        var detected = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);
        when(splitDetectionClient.fetch(eq("AAPL"), any(), any())).thenReturn(List.of(detected));
        when(stockSplitRepository.existsBySymbolAndEffectiveDate("AAPL", LocalDate.of(2020, 8, 31)))
                .thenReturn(false);
        when(transactionRepository.findDistinctTenantIdsBySymbol("AAPL"))
                .thenReturn(List.of(java.util.UUID.randomUUID()));

        runner.runIfNeeded();

        assertThat(appender.list).isEmpty();
    }
}
