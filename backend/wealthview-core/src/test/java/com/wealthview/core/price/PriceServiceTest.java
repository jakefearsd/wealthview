package com.wealthview.core.price;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.dto.BulkPriceRequest;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.core.price.dto.YahooFetchRequest;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    @Mock
    private PriceRepository priceRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private YahooPriceClient yahooPriceClient;

    @InjectMocks
    private PriceService priceService;

    @Test
    void createPrice_validRequest_returnsPriceResponse() {
        var request = new PriceRequest("AAPL", LocalDate.now(), new BigDecimal("185.50"));
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.createPrice(request);

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.source()).isEqualTo("manual");
    }

    @Test
    void getLatestPrice_existing_returnsPrice() {
        var price = new PriceEntity("MSFT", LocalDate.now(), new BigDecimal("420.00"), "manual");
        when(priceRepository.findFirstBySymbolOrderByDateDesc("MSFT"))
                .thenReturn(Optional.of(price));

        var result = priceService.getLatestPrice("MSFT");

        assertThat(result.symbol()).isEqualTo("MSFT");
    }

    @Test
    void getLatestPrice_notFound_throwsEntityNotFound() {
        when(priceRepository.findFirstBySymbolOrderByDateDesc("XYZ"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> priceService.getLatestPrice("XYZ"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listLatestPrices_returnsLatestPerSymbolSortedBySymbol() {
        var aaplPrice = new PriceEntity("AAPL", LocalDate.of(2025, 1, 15), new BigDecimal("185.50"), "yahoo");
        var msftPrice = new PriceEntity("MSFT", LocalDate.of(2025, 1, 15), new BigDecimal("420.00"), "manual");
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of(aaplPrice, msftPrice));

        var result = priceService.listLatestPrices();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.get(1).symbol()).isEqualTo("MSFT");
        assertThat(result.get(1).closePrice()).isEqualByComparingTo("420.00");
    }

    @Test
    void listLatestPrices_noPrices_returnsEmptyList() {
        when(priceRepository.findLatestPerSymbol()).thenReturn(List.of());

        var result = priceService.listLatestPrices();

        assertThat(result).isEmpty();
    }

    @Test
    void getSyncStatus_returnsStatusForEachSymbol() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("AAPL", "MSFT"));
        when(priceRepository.findFirstBySymbolOrderByDateDesc("AAPL"))
                .thenReturn(Optional.of(
                        new PriceEntity("AAPL", LocalDate.now(), new BigDecimal("185.50"), "finnhub")));
        when(priceRepository.findFirstBySymbolOrderByDateDesc("MSFT"))
                .thenReturn(Optional.of(
                        new PriceEntity("MSFT", LocalDate.now().minusDays(5), new BigDecimal("420.00"), "manual")));

        var result = priceService.getSyncStatus();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).stale()).isFalse();
        assertThat(result.get(1).symbol()).isEqualTo("MSFT");
        assertThat(result.get(1).stale()).isTrue();
    }

    @Test
    void getSyncStatus_symbolWithNoPrice_markedStale() {
        when(holdingRepository.findDistinctSymbols()).thenReturn(List.of("XYZ"));
        when(priceRepository.findFirstBySymbolOrderByDateDesc("XYZ"))
                .thenReturn(Optional.empty());

        var result = priceService.getSyncStatus();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("XYZ");
        assertThat(result.get(0).latestDate()).isNull();
        assertThat(result.get(0).source()).isNull();
        assertThat(result.get(0).stale()).isTrue();
    }

    @Test
    void syncFromYahoo_successfulFetch_upsertsAndReturnsResult() {
        var today = LocalDate.now();
        when(yahooPriceClient.fetchHistory("AAPL", today.minusDays(5), today))
                .thenReturn(List.of(
                        new YahooPriceClient.PricePoint(today.minusDays(1), new BigDecimal("185.50")),
                        new YahooPriceClient.PricePoint(today, new BigDecimal("186.00"))));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.syncFromYahoo(List.of("AAPL"));

        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(0);
        assertThat(result.failed()).isEmpty();
        verify(priceRepository, times(2)).save(any(PriceEntity.class));
    }

    @Test
    void syncFromYahoo_existingPrice_updatesAndCounts() {
        var today = LocalDate.now();
        var existingPrice = new PriceEntity("AAPL", today, new BigDecimal("184.00"), "finnhub");
        when(yahooPriceClient.fetchHistory("AAPL", today.minusDays(5), today))
                .thenReturn(List.of(
                        new YahooPriceClient.PricePoint(today, new BigDecimal("186.00"))));
        when(priceRepository.findById(new PriceId("AAPL", today)))
                .thenReturn(Optional.of(existingPrice));
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.syncFromYahoo(List.of("AAPL"));

        assertThat(result.inserted()).isEqualTo(0);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    void syncFromYahoo_failedSymbol_addedToFailedList() {
        var today = LocalDate.now();
        when(yahooPriceClient.fetchHistory("BAD", today.minusDays(5), today))
                .thenReturn(List.of());

        var result = priceService.syncFromYahoo(List.of("BAD"));

        assertThat(result.inserted()).isEqualTo(0);
        assertThat(result.updated()).isEqualTo(0);
        assertThat(result.failed()).containsExactly("BAD");
    }

    @Test
    void syncFromYahoo_nullClient_allSymbolsFail() {
        var service = new PriceService(priceRepository, holdingRepository, null);

        var result = service.syncFromYahoo(List.of("AAPL", "MSFT"));

        assertThat(result.failed()).containsExactly("AAPL", "MSFT");
        verify(priceRepository, never()).save(any());
    }

    @Test
    void fetchFromYahoo_returnsPriceResponses() {
        var request = new YahooFetchRequest(
                List.of("AAPL"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 5));
        when(yahooPriceClient.fetchHistory("AAPL", request.fromDate(), request.toDate()))
                .thenReturn(List.of(
                        new YahooPriceClient.PricePoint(
                                LocalDate.of(2024, 1, 2), new BigDecimal("185.50"))));

        var result = priceService.fetchFromYahoo(request);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).closePrice()).isEqualByComparingTo("185.50");
        assertThat(result.get(0).source()).isEqualTo("yahoo");
    }

    @Test
    void bulkUpsertPrices_newPrices_insertsAll() {
        var prices = List.of(
                new BulkPriceRequest.PriceEntry("AAPL", LocalDate.now(), new BigDecimal("185.50")),
                new BulkPriceRequest.PriceEntry("MSFT", LocalDate.now(), new BigDecimal("420.00")));
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.bulkUpsertPrices(prices, "yahoo");

        assertThat(result).isEqualTo(2);
        verify(priceRepository, times(2)).save(any(PriceEntity.class));
    }

    @Test
    void bulkUpsertPrices_existingPrice_updates() {
        var today = LocalDate.now();
        var existing = new PriceEntity("AAPL", today, new BigDecimal("184.00"), "finnhub");
        when(priceRepository.findById(new PriceId("AAPL", today))).thenReturn(Optional.of(existing));
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var prices = List.of(
                new BulkPriceRequest.PriceEntry("AAPL", today, new BigDecimal("186.00")));

        var result = priceService.bulkUpsertPrices(prices, "yahoo");

        assertThat(result).isEqualTo(1);
        assertThat(existing.getClosePrice()).isEqualByComparingTo("186.00");
        assertThat(existing.getSource()).isEqualTo("yahoo");
    }

    @Test
    void importCsv_validCsv_upsertsAndReturnsResult() throws Exception {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,185.50
                MSFT,2024-01-02,370.25
                """;
        var stream = new java.io.ByteArrayInputStream(csv.getBytes());
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.importCsv(stream);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
        verify(priceRepository, times(2)).save(any(PriceEntity.class));
    }

    @Test
    void importCsv_mixedValidAndInvalid_returnsPartialResult() throws Exception {
        var csv = """
                symbol,date,close_price
                AAPL,2024-01-02,185.50
                MSFT,bad-date,370.25
                """;
        var stream = new java.io.ByteArrayInputStream(csv.getBytes());
        when(priceRepository.findById(any(PriceId.class))).thenReturn(Optional.empty());
        when(priceRepository.save(any(PriceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = priceService.importCsv(stream);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
    }

    @Test
    void browseSymbol_returnsPricesInDateRange() {
        var from = LocalDate.of(2024, 1, 1);
        var to = LocalDate.of(2024, 1, 5);
        var prices = List.of(
                new PriceEntity("AAPL", LocalDate.of(2024, 1, 3), new BigDecimal("185.00"), "yahoo"),
                new PriceEntity("AAPL", LocalDate.of(2024, 1, 2), new BigDecimal("184.50"), "yahoo")
        );
        when(priceRepository.findBySymbolAndDateBetweenOrderByDateDesc("AAPL", from, to))
                .thenReturn(prices);

        var result = priceService.browseSymbol("AAPL", from, to);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).date()).isEqualTo(LocalDate.of(2024, 1, 3));
        assertThat(result.get(1).date()).isEqualTo(LocalDate.of(2024, 1, 2));
    }

    @Test
    void browseSymbol_noPrices_returnsEmptyList() {
        var from = LocalDate.of(2024, 1, 1);
        var to = LocalDate.of(2024, 1, 5);
        when(priceRepository.findBySymbolAndDateBetweenOrderByDateDesc("XYZ", from, to))
                .thenReturn(List.of());

        var result = priceService.browseSymbol("XYZ", from, to);

        assertThat(result).isEmpty();
    }

    @Test
    void deletePrice_existing_deletesSuccessfully() {
        var symbol = "AAPL";
        var date = LocalDate.of(2024, 1, 2);
        when(priceRepository.existsById(new PriceId(symbol, date))).thenReturn(true);

        priceService.deletePrice(symbol, date);

        verify(priceRepository).deleteBySymbolAndDate(symbol, date);
    }

    @Test
    void deletePrice_notFound_throwsEntityNotFound() {
        var symbol = "XYZ";
        var date = LocalDate.of(2024, 1, 2);
        when(priceRepository.existsById(new PriceId(symbol, date))).thenReturn(false);

        assertThatThrownBy(() -> priceService.deletePrice(symbol, date))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("XYZ")
                .hasMessageContaining("2024-01-02");
    }
}
