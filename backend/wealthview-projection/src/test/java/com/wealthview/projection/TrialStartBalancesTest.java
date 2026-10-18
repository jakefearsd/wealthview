package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrialStartBalancesTest {

    private static HypotheticalAccountInput account(String balance, String accountType, String owner) {
        return new HypotheticalAccountInput(new BigDecimal(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), new BigDecimal(balance), accountType, owner);
    }

    private static TrialStartBalances withTotal(double total) {
        return new TrialStartBalances(total, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void of_mixedPoolsAndOwners_sumsEachPoolAndOwnerSlice() {
        var sources = PoolReturnModel.from(List.of(
                account("100", "taxable", "joint"),
                account("50", "taxable", "spouse"),
                account("200", "traditional", "primary"),
                account("80", "traditional", "spouse"),
                account("30", "roth", "spouse")), 0.0).allAccounts();

        var start = TrialStartBalances.of(sources, new double[]{100, 50, 200, 80, 30});

        assertThat(start.taxable()).isEqualTo(150);
        assertThat(start.traditional()).isEqualTo(280);
        assertThat(start.roth()).isEqualTo(30);
        assertThat(start.traditionalSpouse()).isEqualTo(80);
        assertThat(start.rothSpouse()).isEqualTo(30);
        assertThat(start.taxableJoint()).isEqualTo(100);
        assertThat(start.taxablePrimary()).isZero();
        assertThat(start.taxableSpouse()).isEqualTo(50);
        assertThat(start.total()).isEqualTo(460);
    }

    @Test
    void of_lengthMismatch_throwsIllegalArgument() {
        var sources = PoolReturnModel.from(List.of(
                account("100", "taxable", "joint"),
                account("50", "taxable", "primary")), 0.0).allAccounts();

        assertThatThrownBy(() -> TrialStartBalances.of(sources, new double[]{100}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void medianTotal_oddCount_returnsMiddleValue() {
        var starts = new TrialStartBalances[]{withTotal(300), withTotal(100), withTotal(200)};

        assertThat(TrialStartBalances.medianTotal(starts)).isEqualTo(200);
    }

    @Test
    void medianTotal_evenCount_averagesMiddleTwo() {
        var starts = new TrialStartBalances[]{withTotal(100), withTotal(400), withTotal(200), withTotal(300)};

        assertThat(TrialStartBalances.medianTotal(starts)).isEqualTo(250);
    }

    @Test
    void medianTotal_empty_throwsIllegalArgument() {
        assertThatThrownBy(() -> TrialStartBalances.medianTotal(new TrialStartBalances[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
