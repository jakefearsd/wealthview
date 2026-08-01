package com.wealthview.core.split.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.wealthview.persistence.entity.StockSplitEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two split DTOs, neither of which had a test (the package sat at 40% line coverage).
 *
 * <p>Both are small, but both are the kind of small that fails silently. A split ratio is two
 * adjacent {@code int}s and the response carries two adjacent {@code String}s — transposing either
 * pair compiles cleanly and produces a plausible-looking record, while inverting a ratio turns a
 * 4:1 split into a 1:4 reverse split and multiplies every holder's share count by 1/16 of what it
 * should be.
 */
class SplitDtoTest {

    @Test
    void from_mapsEveryFieldToItsOwnComponent() {
        var entity = new StockSplitEntity("AAPL", LocalDate.of(2020, 8, 31), 4, 1, "finnhub");
        var appliedAt = OffsetDateTime.of(2026, 3, 5, 9, 30, 0, 0, ZoneOffset.UTC);
        entity.setAppliedAt(appliedAt);
        entity.setNotes("backfilled");

        var response = StockSplitResponse.from(entity);

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.effectiveDate()).isEqualTo(LocalDate.of(2020, 8, 31));
        assertThat(response.numerator())
                .as("numerator and denominator are adjacent ints — a transposition would turn a "
                        + "4:1 split into a 1:4 reverse split")
                .isEqualTo(4);
        assertThat(response.denominator()).isEqualTo(1);
        assertThat(response.source())
                .as("source and notes are adjacent Strings and would transpose silently")
                .isEqualTo("finnhub");
        assertThat(response.notes()).isEqualTo("backfilled");
        assertThat(response.appliedAt()).isEqualTo(appliedAt);
    }

    @Test
    void from_splitWithoutNotes_carriesNullNotesButAlwaysAnAppliedAt() {
        var entity = new StockSplitEntity("NVDA", LocalDate.of(2024, 6, 10), 10, 1, "manual");

        var response = StockSplitResponse.from(entity);

        assertThat(response.notes()).isNull();
        assertThat(response.appliedAt())
                .as("applied_at is NOT NULL in the schema and defaults to now() on construction, "
                        + "so the response can never surface it as absent")
                .isNotNull();
        assertThat(response.numerator()).isEqualTo(10);
        assertThat(response.source()).isEqualTo("manual");
    }

    // === DetectedSplit ratio validation ===

    @Test
    void detectedSplit_positiveRatio_isAccepted() {
        var split = new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1);

        assertThat(split.numerator()).isEqualTo(4);
        assertThat(split.denominator()).isEqualTo(1);
    }

    @Test
    void detectedSplit_reverseSplitRatio_isStillAccepted() {
        // 1:10 is a legitimate reverse split, not an inverted 10:1.
        var split = new DetectedSplit("XYZ", LocalDate.of(2025, 1, 2), 1, 10);

        assertThat(split.numerator()).isEqualTo(1);
        assertThat(split.denominator()).isEqualTo(10);
    }

    @Test
    void detectedSplit_zeroNumerator_isRejected() {
        // A zero would zero out every holder's share count when the split is applied.
        assertThatThrownBy(() -> new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split ratio must be positive");
    }

    @Test
    void detectedSplit_zeroDenominator_isRejected() {
        assertThatThrownBy(() -> new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split ratio must be positive");
    }

    @Test
    void detectedSplit_negativeRatio_isRejected() {
        assertThatThrownBy(() -> new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), -4, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
