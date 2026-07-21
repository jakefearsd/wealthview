package com.wealthview.core.price;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.entity.PriceEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PriceHistorySupportTest {

    @Test
    void weeklyFridaysWithEndDate_typicalRange_returnsEveryFridayPlusEndDate() {
        var start = LocalDate.of(2026, 7, 1);  // a Wednesday
        var end = LocalDate.of(2026, 7, 20);   // a Monday

        var dates = PriceHistorySupport.weeklyFridaysWithEndDate(start, end);

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 17),
                end);
        assertThat(dates.subList(0, dates.size() - 1))
                .allSatisfy(d -> assertThat(d.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY));
    }

    @Test
    void weeklyFridaysWithEndDate_endDateIsAFriday_doesNotDuplicateIt() {
        var start = LocalDate.of(2026, 7, 1);
        var end = LocalDate.of(2026, 7, 17);   // a Friday

        var dates = PriceHistorySupport.weeklyFridaysWithEndDate(start, end);

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 7, 3),
                LocalDate.of(2026, 7, 10),
                end);
    }

    @Test
    void weeklyFridaysWithEndDate_rangeShorterThanAWeek_stillIncludesEndDate() {
        var start = LocalDate.of(2026, 7, 18); // Saturday
        var end = LocalDate.of(2026, 7, 20);   // Monday — no Friday in range

        var dates = PriceHistorySupport.weeklyFridaysWithEndDate(start, end);

        assertThat(dates).containsExactly(end);
    }

    @Test
    void buildPriceMap_multipleSymbols_groupsBySymbolWithNavigableDates() {
        var prices = List.of(
                new PriceEntity("AAPL", LocalDate.of(2026, 1, 2), new BigDecimal("150.0000"), "seed"),
                new PriceEntity("AAPL", LocalDate.of(2026, 1, 9), new BigDecimal("155.0000"), "seed"),
                new PriceEntity("VOO", LocalDate.of(2026, 1, 2), new BigDecimal("520.0000"), "seed"));

        var priceMap = PriceHistorySupport.buildPriceMap(prices);

        assertThat(priceMap).containsOnlyKeys("AAPL", "VOO");
        // NavigableMap semantics: floorEntry finds the latest price at or before a date —
        // the carry-forward lookup both history services rely on.
        assertThat(priceMap.get("AAPL").floorEntry(LocalDate.of(2026, 1, 8)).getValue())
                .isEqualByComparingTo("150.0000");
        assertThat(priceMap.get("AAPL").floorEntry(LocalDate.of(2026, 1, 9)).getValue())
                .isEqualByComparingTo("155.0000");
    }

    @Test
    void buildPriceMap_noPrices_returnsEmptyMap() {
        assertThat(PriceHistorySupport.buildPriceMap(List.of())).isEmpty();
    }
}
