package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.projection.WithdrawalTaxBillSolver.BillInputs;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawalTaxBillSolverTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2030;
    private static final int EARLY_ACCESS_AGE = 60;

    private static BillInputs inputs(int age, BigDecimal traditionalSpendIncome, FilingStatus status) {
        return new BillInputs(YEAR, ZERO, ZERO, traditionalSpendIncome, ZERO, ZERO, ZERO, ZERO, ZERO, age,
                bd("100000"), bd("100000"), status, ZERO, ZERO);
    }

    private static WithdrawalTaxBillSolver solver(TaxCalculationStrategy tax) {
        return new WithdrawalTaxBillSolver(tax, null, null, 2025, ZERO, EARLY_ACCESS_AGE);
    }

    private static TaxCalculationStrategy stubbedTax(CombinedTaxResult result) {
        var tax = mock(TaxCalculationStrategy.class);
        when(tax.computeDetailedTax(any(), anyInt(), any(), any(), any())).thenReturn(result);
        return tax;
    }

    private static CombinedTaxResult detailed1500() {
        return new CombinedTaxResult(bd("1500"), ZERO, bd("1500"), ZERO, ZERO, false);
    }

    @Test
    void settle_belowEarlyAccessAge_chargesTenPercentPenalty() {
        var solver = solver(null);

        var bill = solver.settle(inputs(55, bd("10000"), FilingStatus.SINGLE), x -> ZERO);

        assertThat(bill.earlyWithdrawalPenalty()).isEqualByComparingTo(bd("1000"));
        assertThat(bill.total()).isEqualByComparingTo(bd("1000"));
    }

    @Test
    void settle_atEarlyAccessAge_chargesNoPenalty() {
        var solver = solver(null);

        var bill = solver.settle(inputs(60, bd("10000"), FilingStatus.SINGLE), x -> ZERO);

        assertThat(bill.earlyWithdrawalPenalty()).isEqualByComparingTo(ZERO);
        assertThat(bill.total()).isEqualByComparingTo(ZERO);
    }

    @Test
    void settle_nothingRecordedWhenNoIncomeAndNoTax() {
        var tax = mock(TaxCalculationStrategy.class);
        var solver = solver(tax);

        var bill = solver.settle(inputs(65, ZERO, FilingStatus.SINGLE), x -> ZERO);

        assertThat(bill.recordedBreakdown()).isEmpty();
        verifyNoInteractions(tax);
    }

    @Test
    void settle_recordsLastBreakdownOnlyWhenIncomeOrTax() {
        var result = detailed1500();
        var solver = solver(stubbedTax(result));

        var bill = solver.settle(inputs(65, bd("20000"), FilingStatus.SINGLE), x -> ZERO);

        assertThat(bill.recordedBreakdown()).contains(result);
        assertThat(bill.total()).isEqualByComparingTo(bd("1500"));
    }

    @Test
    void settle_usesFilingStatusFromInputs() {
        var tax = stubbedTax(detailed1500());
        var solver = solver(tax);

        solver.settle(inputs(65, bd("20000"), FilingStatus.SINGLE), x -> ZERO);

        verify(tax).computeDetailedTax(any(), eq(YEAR), eq(FilingStatus.SINGLE), any(), any());
        verify(tax, never()).computeDetailedTax(any(), anyInt(), eq(FilingStatus.MARRIED_FILING_JOINTLY),
                any(), any());
    }

    @Test
    void settle_noEmbeddedGain_returnsGainZeroBillAfterOnePeek() {
        var solver = solver(stubbedTax(detailed1500()));
        var peeks = new AtomicInteger();

        var bill = solver.settle(inputs(65, bd("20000"), FilingStatus.SINGLE), sale -> {
            peeks.incrementAndGet();
            return ZERO;
        });

        assertThat(peeks.get()).isEqualTo(1);
        assertThat(bill.total()).isEqualByComparingTo(bd("1500"));
    }
}
