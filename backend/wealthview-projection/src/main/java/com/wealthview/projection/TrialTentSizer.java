package com.wealthview.projection;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.strategy.WithdrawalOrder;

/**
 * Sizes the Monte Carlo bond tent for one pass from that pass's schedule. Each covered year's withdrawal is
 * spending less income plus every tax the year's draw costs, taken from an expected-path walk of the three
 * pools. The walk ignores growth and RMDs: it is a short-horizon tax estimate, not a projection. The whole
 * tax bill counts, conversion tax included, as in the deterministic sizing.
 */
final class TrialTentSizer {

    /**
     * The pass-level inputs. {@code tables}/{@code baseIncome} price withdrawal and conversion tax (null: no
     * tax modeling, and {@code conversionTaxByYear} prices conversions instead); {@code conversionByYear} and
     * {@code dsCeiling} are per retirement year; the balances are the expected start balances.
     */
    record Inputs(@Nullable OrdinaryTaxTable[] tables, @Nullable double[] baseIncome,
                  @Nullable double[] conversionByYear, @Nullable double[] conversionTaxByYear,
                  @Nullable double[] dsCeiling, double taxable, double traditional, double roth,
                  WithdrawalOrder order, int retirementAge, int earlyAccessAge) {
    }

    /** The tent target and the covered years' gross withdrawals it was sized from. */
    record Sizing(double target, double[] withdrawals) {
    }

    private TrialTentSizer() {
    }

    /** The tent target: the present value of the covered withdrawals at the plan's sizing yield. */
    // UseVarargs: the schedules are per-year arrays, not variable arguments.
    @SuppressWarnings("PMD.UseVarargs")
    static double target(TrialTentPlan plan, Inputs in, double[] floors, double[] discretionary, double[] income,
                         double[] surplusTax) {
        return sizing(plan, in, floors, discretionary, income, surplusTax).target();
    }

    /** The target together with the withdrawals behind it. */
    // UseVarargs: the schedules are per-year arrays, not variable arguments.
    @SuppressWarnings("PMD.UseVarargs")
    static Sizing sizing(TrialTentPlan plan, Inputs in, double[] floors, double[] discretionary, double[] income,
                         double[] surplusTax) {
        double[] withdrawals = withdrawals(plan, in, floors, discretionary, income, surplusTax);
        return new Sizing(BondTentSizer.target(withdrawals, plan.coverageYears(), plan.sizingRealYield()),
                withdrawals);
    }

    /** The gross withdrawal for each covered year, from the expected-path walk. */
    // UseVarargs: the schedules are per-year arrays, not variable arguments.
    @SuppressWarnings("PMD.UseVarargs")
    static double[] withdrawals(TrialTentPlan plan, Inputs in, double[] floors, double[] discretionary,
                                double[] income, double[] surplusTax) {
        int covered = (int) Math.min(Math.ceil(plan.coverageYears()), floors.length);
        double[] withdrawals = new double[covered];
        double taxable = in.taxable();
        double traditional = in.traditional();
        double roth = in.roth();
        var tables = in.tables();
        var conversions = in.conversionByYear();
        var baseIncome = in.baseIncome();
        var ceilings = in.dsCeiling();
        for (int k = 0; k < covered; k++) {
            double spending = floors[k] + discretionary[k];
            double need = Math.max(0, spending - income[k]);
            double conversion = conversions == null ? 0 : Math.min(conversions[k], traditional);
            double base = baseIncome == null ? 0 : baseIncome[k];
            double conversionTax = conversionTax(in, k, conversion, base);
            var split = TrialSimulator.splitWithdrawal(taxable, traditional, roth, need, in.order(),
                    conversions != null && in.retirementAge() + k < in.earlyAccessAge(),
                    ceilings == null ? 0 : ceilings[k], income[k], conversion, 0);
            double withdrawalTax = tables != null && split.traditional() > 0
                    ? tables[k].incrementalTax(base + conversion, split.traditional()) : 0;
            withdrawals[k] = Math.max(0, spending - income[k] + surplusTax[k] + conversionTax + withdrawalTax);
            taxable = Math.max(0, taxable - split.taxable());
            traditional = Math.max(0, traditional - conversion - split.traditional());
            roth = Math.max(0, roth + conversion - split.roth());
        }
        return withdrawals;
    }

    private static double conversionTax(Inputs in, int k, double conversion, double base) {
        if (conversion <= 0) {
            return 0;
        }
        var tables = in.tables();
        if (tables != null) {
            return tables[k].incrementalTax(base, conversion);
        }
        var fixed = in.conversionTaxByYear();
        return fixed == null ? 0 : fixed[k];
    }
}
