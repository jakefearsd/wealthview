package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class CombinedTaxResultTest {

    @Test
    void zero_constant_hasAllZeroAmountsAndNoItemization() {
        assertThat(CombinedTaxResult.ZERO.federalTax()).isEqualByComparingTo(bd("0"));
        assertThat(CombinedTaxResult.ZERO.stateTax()).isEqualByComparingTo(bd("0"));
        assertThat(CombinedTaxResult.ZERO.totalTax()).isEqualByComparingTo(bd("0"));
        assertThat(CombinedTaxResult.ZERO.saltDeduction()).isEqualByComparingTo(bd("0"));
        assertThat(CombinedTaxResult.ZERO.itemizedDeductions()).isEqualByComparingTo(bd("0"));
        assertThat(CombinedTaxResult.ZERO.usedItemized()).isFalse();
    }

    @Test
    void add_taxAmounts_accumulate() {
        var a = new CombinedTaxResult(bd("100"), bd("20"), bd("120"), bd("0"), bd("0"), false);
        var b = new CombinedTaxResult(bd("50"), bd("5"), bd("55"), bd("0"), bd("0"), false);

        CombinedTaxResult sum = a.add(b);

        assertThat(sum.federalTax()).isEqualByComparingTo(bd("150"));
        assertThat(sum.stateTax()).isEqualByComparingTo(bd("25"));
        assertThat(sum.totalTax()).isEqualByComparingTo(bd("175"));
    }

    @Test
    void add_otherHasPositiveSaltAndItemized_otherValuesWin() {
        var base = new CombinedTaxResult(bd("100"), bd("20"), bd("120"), bd("8000"), bd("15000"), true);
        var latest = new CombinedTaxResult(bd("10"), bd("2"), bd("12"), bd("9000"), bd("17000"), true);

        CombinedTaxResult sum = base.add(latest);

        assertThat(sum.saltDeduction()).isEqualByComparingTo(bd("9000"));
        assertThat(sum.itemizedDeductions()).isEqualByComparingTo(bd("17000"));
    }

    @Test
    void add_otherHasZeroSaltAndItemized_existingValuesAreRetained() {
        var base = new CombinedTaxResult(bd("100"), bd("20"), bd("120"), bd("8000"), bd("15000"), true);
        var latest = new CombinedTaxResult(bd("10"), bd("2"), bd("12"), bd("0"), bd("0"), false);

        CombinedTaxResult sum = base.add(latest);

        assertThat(sum.saltDeduction()).isEqualByComparingTo(bd("8000"));
        assertThat(sum.itemizedDeductions()).isEqualByComparingTo(bd("15000"));
    }

    @Test
    void add_usedItemized_isTrueWhenEitherSideUsedItemized() {
        var neither = CombinedTaxResult.ZERO.add(CombinedTaxResult.ZERO);
        var leftOnly = new CombinedTaxResult(bd("1"), bd("1"), bd("2"), bd("0"), bd("0"), true)
                .add(CombinedTaxResult.ZERO);
        var rightOnly = CombinedTaxResult.ZERO
                .add(new CombinedTaxResult(bd("1"), bd("1"), bd("2"), bd("0"), bd("0"), true));

        assertThat(neither.usedItemized()).isFalse();
        assertThat(leftOnly.usedItemized()).isTrue();
        assertThat(rightOnly.usedItemized()).isTrue();
    }
}
