package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.CombinedTaxResult;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MultiPoolYearDtoBuilder#build} surfacing of the per-year RMD amount and long-term
 * capital-gains tax onto {@link com.wealthview.core.projection.dto.ProjectionYearDto.TaxBreakdown}.
 * Both values are already computed upstream by {@link DeterministicProjectionEngine}; this test
 * pins only the threading + "positive value or null" convention, not the engine math itself.
 */
class MultiPoolYearDtoBuilderTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2040;
    private static final int AGE_RETIRED = 75;

    private static MultiPoolYearDtoBuilder.YearDtoInputs inputs(boolean retired, BigDecimal rmdAmount,
                                                                  BigDecimal ltcgTax) {
        return new MultiPoolYearDtoBuilder.YearDtoInputs(
                YEAR, AGE_RETIRED, bd("500000"), ZERO, ZERO, bd("50000"), retired,
                ZERO, bd("20000"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                bd("10000"), bd("40000"), ZERO,
                PoolStrategy.TaxSourceResult.ZERO,
                bd("450000"), bd("100000"), bd("300000"), bd("50000"),
                rmdAmount, ltcgTax, ZERO);
    }

    @Test
    void build_retiredYearWithRmdAndLtcg_surfacesBothOnTaxBreakdown() {
        var in = inputs(true, bd("12000"), bd("1500"));

        var dto = MultiPoolYearDtoBuilder.build(in, CombinedTaxResult.ZERO);

        assertThat(dto.tax().rmdAmount()).isEqualByComparingTo(bd("12000"));
        assertThat(dto.tax().capitalGainsTax()).isEqualByComparingTo(bd("1500"));
    }

    @Test
    void build_nonRetiredYearWithZeroRmdAndLtcg_leavesBothNull() {
        var in = inputs(false, ZERO, ZERO);

        var dto = MultiPoolYearDtoBuilder.build(in, null);

        assertThat(dto.tax().rmdAmount()).isNull();
        assertThat(dto.tax().capitalGainsTax()).isNull();
    }

    /**
     * T23 item 1: a stale pre-annotator {@code CombinedTaxResult} (e.g. left over from an earlier
     * bundle in the year's tax cascade -- see {@code MultiPool#lastTaxBreakdown}'s javadoc) must
     * never surface federal/state breakdown fields for a year whose {@code taxLiability} nets to
     * zero (fully absorbed elsewhere this year). The breakdown is a decomposition of
     * {@code taxLiability}; when there is no taxLiability left to decompose, every breakdown field
     * must be null too -- the same "positive value or null" convention every other tax field on
     * this DTO already follows.
     */
    @Test
    void build_zeroTaxLiabilityWithStaleTaxBreakdown_clearsFederalStateSaltAndItemizedFields() {
        var in = new MultiPoolYearDtoBuilder.YearDtoInputs(
                YEAR, AGE_RETIRED, bd("500000"), ZERO, ZERO, bd("50000"), true,
                ZERO, ZERO,
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                bd("10000"), bd("40000"), ZERO,
                PoolStrategy.TaxSourceResult.ZERO,
                bd("450000"), bd("100000"), bd("300000"), bd("50000"),
                ZERO, ZERO, ZERO);
        var staleBreakdown = new CombinedTaxResult(
                bd("5000"), bd("500"), bd("5500"), bd("1000"), bd("14600"), true);

        var dto = MultiPoolYearDtoBuilder.build(in, staleBreakdown);

        assertThat(dto.taxLiability()).isNull();
        assertThat(dto.federalTax()).isNull();
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
        assertThat(dto.usedItemizedDeduction()).isNull();
    }
}
