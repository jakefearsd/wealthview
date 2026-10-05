package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.common.Money;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.TerminalValue;
import com.wealthview.core.projection.household.HouseholdContext;

/**
 * Phase 1a after-tax legacy: values the LAST projected row's pools for heirs -- the end of the horizon,
 * or the second death's year when the household truncation ended the loop early.
 */
final class TerminalValueResolver {

    private TerminalValueResolver() {
    }

    /**
     * Returns {@code null} when the projection produced no years (e.g. the end age is already behind
     * the reference year). A null pool balance on the last row counts as zero.
     */
    @Nullable
    static TerminalValue resolve(List<ProjectionYearDto> yearlyData, @Nullable HouseholdContext household,
                                 BigDecimal heirTaxRate) {
        if (yearlyData.isEmpty()) {
            return null;
        }
        var last = yearlyData.getLast();
        boolean atSecondDeath = household != null && household.secondDeathYear().isPresent()
                && household.secondDeathYear().get() == last.year();
        return TerminalValue.compute(last.year(), Money.sum(last.traditionalBalance()),
                Money.sum(last.rothBalance()), Money.sum(last.taxableBalance()), heirTaxRate,
                atSecondDeath);
    }
}
