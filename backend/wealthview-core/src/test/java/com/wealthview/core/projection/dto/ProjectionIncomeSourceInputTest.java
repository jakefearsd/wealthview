package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ProjectionIncomeSourceInput.isActiveForAge — the method the projection engine
 * uses every year to decide whether a source contributes income at the current age.
 */
class ProjectionIncomeSourceInputTest {

    private ProjectionIncomeSourceInput recurring(int startAge, Integer endAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Pension", IncomeSourceType.PENSION,
                new BigDecimal("20000"), startAge, endAge, new BigDecimal("0.02"),
                /* oneTime */ false, "taxable",
                null, null, null, null, null, null);
    }

    private ProjectionIncomeSourceInput oneTime(int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Inheritance", IncomeSourceType.OTHER,
                new BigDecimal("50000"), startAge, null, BigDecimal.ZERO,
                /* oneTime */ true, "taxable",
                null, null, null, null, null, null);
    }

    @Test
    void isActiveForAge_recurringBeforeStartAge_returnsFalse() {
        var src = recurring(65, 90);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 64)).isFalse();
    }

    @Test
    void isActiveForAge_recurringAtStartAge_returnsTrue() {
        var src = recurring(65, 90);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 65)).isTrue();
    }

    @Test
    void isActiveForAge_recurringAtEndAge_returnsTrue() {
        var src = recurring(65, 90);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 90)).isTrue();
    }

    @Test
    void isActiveForAge_recurringAfterEndAge_returnsFalse() {
        var src = recurring(65, 90);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 91)).isFalse();
    }

    @Test
    void isActiveForAge_recurringWithNoEndAge_isActiveForever() {
        var src = recurring(62, null);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 62)).isTrue();
        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 120)).isTrue();
        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 61)).isFalse();
    }

    @Test
    void isActiveForAge_oneTimeAtStartAge_isActiveOnlyOnce() {
        var src = oneTime(70);

        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 69)).isFalse();
        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 70)).isTrue();
        assertThat(ProjectionIncomeSourceInput.isActiveForAge(src, 71)).isFalse();
    }
}
