package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * API #22: the Monte Carlo optimizer started every trial at the retirement year from TODAY's
 * balances, skipping the contributions and growth between now ({@code baseYear}) and retirement
 * that the deterministic engine models year by year.
 */
class PreRetirementAccumulationTest {

    private static HypotheticalAccountInput account(String balance, String contribution, String type) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), AssetAllocation.ALL_US,
                Optional.of(bd("0.05")), bd(balance), type, "spouse");
    }

    private static GuardrailOptimizationInputBuilder zeroInflationNoFee(List<ProjectionAccountInput> accounts) {
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(LocalDate.of(2030, 1, 1))
                .withInflationRate(BigDecimal.ZERO)
                .withFeeRate(BigDecimal.ZERO)
                .withAccounts(accounts);
    }

    @Test
    void seedAtRetirement_retirementInBaseYear_returnsInputUnchanged() {
        var input = zeroInflationNoFee(List.of(account("10000", "1000", "taxable"))).withBaseYear(2030).build();

        var seeded = PreRetirementAccumulation.seedAtRetirement(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        assertThat(seeded).isSameAs(input);
    }

    @Test
    void seedAtRetirement_twoYearsOut_contributesThenGrowsEachYearLikeTheDeterministicEngine() {
        var input = zeroInflationNoFee(List.of(account("10000", "1000", "taxable"))).withBaseYear(2028).build();

        var seeded = PreRetirementAccumulation.seedAtRetirement(input, ProjectionTestFixtures.TEST_CMA_MATRIX);

        // ((10000 + 1000) * 1.05 + 1000) * 1.05 = 13177.5; the contributions enter at cost.
        var account = seeded.accounts().getFirst();
        assertThat(account.initialBalance()).isEqualByComparingTo(bd("13177.5"));
        assertThat(account.costBasis()).isEqualByComparingTo(bd("12000"));
        assertThat(account.annualContribution()).isEqualByComparingTo(bd("1000"));
        assertThat(account.accountType()).isEqualTo("taxable");
        assertThat(account.owner()).isEqualTo("spouse");
        assertThat(account.expectedReturnOverride()).contains(bd("0.05"));
    }

    @Test
    void optimize_retirementDecadesAway_firstRetirementYearReflectsAccumulation() {
        var accounts = List.<ProjectionAccountInput>of(account("30000", "45500", "traditional"));
        var optimizer = new MonteCarloSpendingOptimizer(null, ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailYearlySpending first = optimizer.optimize(
                zeroInflationNoFee(accounts).withBaseYear(1997).build()).yearlySpending().getFirst();

        // 33 years of $45,500 at a fixed 5% real compound to roughly $4.0M by retirement -- the
        // first retirement year's median must reflect that, not today's $30,000.
        assertThat(first.portfolioBalanceMedian()).isGreaterThan(bd("3000000"));
    }
}
