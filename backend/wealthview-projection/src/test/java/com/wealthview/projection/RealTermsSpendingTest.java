package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.strategy.FixedPercentageWithdrawal;
import com.wealthview.core.projection.strategy.WithdrawalContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the real-terms (today's-dollars) semantics of the projection: spending is constant real
 * (no nominal escalation year-over-year) and income sources are deflated by scenario inflation so a
 * fixed-nominal source loses purchasing power while a COLA source holds constant real value.
 */
class RealTermsSpendingTest {

    private static final BigDecimal SCENARIO_INFLATION = new BigDecimal("0.025");

    @Test
    void computeWithdrawal_fixedPercentageAfterFirstYear_isConstantRealNotNominallyEscalated() {
        var strategy = new FixedPercentageWithdrawal(new BigDecimal("0.04"));

        var year1 = new WithdrawalContext(new BigDecimal("1000000"), new BigDecimal("1000000"),
                BigDecimal.ZERO, new BigDecimal("0.05"), SCENARIO_INFLATION, 1);
        BigDecimal firstYear = strategy.computeWithdrawal(year1);

        var year2 = new WithdrawalContext(new BigDecimal("1000000"), new BigDecimal("1000000"),
                firstYear, new BigDecimal("0.05"), SCENARIO_INFLATION, 2);
        BigDecimal secondYear = strategy.computeWithdrawal(year2);

        assertThat(secondYear).isEqualByComparingTo(firstYear);
    }

    @Test
    void realAmount_fixedNominalSource_deflatesByScenarioInflation() {
        var source = source(new BigDecimal("30000"), BigDecimal.ZERO);

        // yearsInRetirement = 6 → n = 5 compounding steps; real = 30000 / 1.025^5
        BigDecimal real = IncomeYearMath.realAmount(source, 6, SCENARIO_INFLATION);

        BigDecimal expected = new BigDecimal("30000")
                .divide(new BigDecimal("1.025").pow(5), 4, RoundingMode.HALF_UP);
        assertThat(real).isEqualByComparingTo(expected);
    }

    @Test
    void realAmount_colaSourceMatchingScenarioInflation_isConstantReal() {
        var source = source(new BigDecimal("30000"), SCENARIO_INFLATION);

        BigDecimal real = IncomeYearMath.realAmount(source, 6, SCENARIO_INFLATION);

        assertThat(real).isEqualByComparingTo(new BigDecimal("30000"));
    }

    private static ProjectionIncomeSourceInput source(BigDecimal annualAmount, BigDecimal sourceInflation) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Source", IncomeSourceType.OTHER,
                annualAmount, 60, null, sourceInflation, false, "taxable",
                null, null, null, null, null, null);
    }
}
