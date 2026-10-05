package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** D4 (Phase 1a): the conversion schedule's Bracket column is the marginal rate of the conversion's
 * LAST dollar. Single 2025: $15,000 deduction; 12% ends at $48,475 taxable ($63,475 gross). */
class GuardrailResponseBuilderBracketLabelTest {

    private static OrdinaryTaxTable single2025() {
        var brackets = mock(TaxBracketRepository.class);
        var deductions = mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        return OrdinaryTaxTable.build(new FederalTaxCalculator(brackets, deductions), 2025, FilingStatus.SINGLE, -1);
    }

    @Test
    void bracketLabel_topDollarInTwelvePercent_returnsTwelvePercent() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 50_000)).isEqualTo("12%");
    }

    @Test
    void bracketLabel_exactlyAtTwelvePercentCeiling_isStillTwelveNotTwentyTwo() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 63_475)).isEqualTo("12%");
    }

    @Test
    void bracketLabel_withinStandardDeduction_returnsZeroPercent() {
        assertThat(GuardrailResponseBuilder.bracketLabel(single2025(), 10_000)).isEqualTo("0%");
    }

    @Test
    void bracketLabel_noTable_returnsNull() {
        assertThat(GuardrailResponseBuilder.bracketLabel(null, 50_000)).isNull();
    }
}
