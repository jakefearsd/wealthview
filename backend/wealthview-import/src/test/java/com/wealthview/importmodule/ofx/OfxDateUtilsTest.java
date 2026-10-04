package com.wealthview.importmodule.ofx;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfxDateUtilsTest {

    @Test
    void toLocalDate_gmtMidnight_returnsSameCalendarDate() {
        var startOfGmtDay = Instant.parse("2025-01-10T00:00:00Z");

        var date = OfxDateUtils.toLocalDate(startOfGmtDay);

        assertThat(date).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    void toLocalDate_lastSecondOfGmtDay_returnsSameCalendarDate() {
        var endOfGmtDay = Instant.parse("2025-01-10T23:59:59Z");

        var date = OfxDateUtils.toLocalDate(endOfGmtDay);

        assertThat(date).isEqualTo(LocalDate.of(2025, 1, 10));
    }
}
