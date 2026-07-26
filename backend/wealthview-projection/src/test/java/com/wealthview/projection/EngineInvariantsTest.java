package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.mock;

/**
 * Consolidated cross-cutting invariant suite for {@link DeterministicProjectionEngine} (audit
 * T18b). Runs a parameterized matrix of scenario shapes and, per scenario-year, pins four
 * structural properties that must hold across EVERY shape the engine supports rather than one
 * fixture at a time: the aggregate balance identity, the federal/state tax reconciliation
 * identity (T18a-5b), monotonic direction properties under three scenario knobs, and RMD
 * correctness against the {@link RmdCalculator} oracle.
 *
 * <p><strong>Not a duplicate of its siblings</strong> (see class javadocs there for scope):
 * {@link GuardrailRulesTaxDynamicsMonotonicityTest} (T16) pins a DIFFERENT monotonicity claim —
 * that the Monte Carlo guardrail engine's with-rules success probability never regresses relative
 * to the no-rules schedule — on the stochastic {@code TrialSimulator}, not this deterministic
 * engine. {@code TrialSimulatorReturnTest}'s composition-identity tests (T12) pin the Monte Carlo
 * engine's per-trial ordinary-tax stacking telescoping identity. This class is exclusively about
 * {@link DeterministicProjectionEngine} and the four invariants below.
 *
 * <h2>Invariant 1 — balance identity</h2>
 * {@code end == start + contributions + growth + surplusReinvested + rmdAmount - withdrawals -
 * taxLiability}, within a per-year tolerance. The {@code + rmdAmount} term is REQUIRED for
 * correctness (not part of the task brief's prose, which omits it) — confirmed against
 * {@code DeterministicProjectionEngineCharacterizationTest#run_threePoolWithdrawalRateScenario_
 * endBalanceChainIsConsistent}, the engine's own pinned chain-consistency formula: a forced RMD is
 * an internal traditional-to-taxable transfer (T18a-5a: {@code withdrawals} counts it as an
 * outflow even though it never leaves the total portfolio), so it must be added back.
 *
 * <p>The task brief documents two exclusion cases where the naive identity (using {@code
 * taxLiability} — the DTO's headline number — as "pool-funded taxes", rather than the finer-
 * grained {@code taxPaidFrom*} pool-funded breakdown) can deviate beyond a flat $1/year: (a) the
 * {@code RetirementWithdrawalProcessor} surplus branch, where {@code 0 <= grossSurplus <=
 * totalObligation} lets some of the tax bill be funded from CASH SURPLUS (active income exceeding
 * spending) rather than a pool debit — that slice never touches the pool, so subtracting the full
 * {@code taxLiability} over-counts by exactly {@code min(grossSurplus, totalObligation)}; and (b)
 * the audit-C2 traditional tax gross-up fixed point, which converges to within {@code
 * MultiPool.GROSS_UP_TOLERANCE} ($1) of the true self-consistent bill, not exactly.
 *
 * <p>Rather than special-casing scenario shapes, both exclusions are folded into ONE per-year
 * tolerance formula, derived entirely from exposed DTO fields (verified exact: {@code
 * ProjectionYearDto.incomeStreamsTotal()}/{@code essentialExpenses()}/{@code
 * discretionaryExpenses()} are populated from the SAME {@code SpendingPlan#resolveYear} call
 * (same inputs, pure function) that {@code RetirementWithdrawalProcessor} uses internally, so
 * {@code grossSurplusApprox} below reconstructs the production {@code grossSurplus} EXACTLY):
 *
 * <pre>
 * grossSurplusApprox = incomeStreamsTotal - essentialExpenses - discretionaryExpenses
 * tolerance           = max(grossSurplusApprox, 0) + $1
 * </pre>
 *
 * This collapses to the flat $1/year tolerance whenever there is no active-income surplus (the
 * general case, including every non-retired and every deficit year), and grows exactly enough to
 * cover the surplus-funded-tax-slice exclusion when one is active — so the exclusion is PINNED
 * (its magnitude is bounded by a value derived from the model, not an arbitrarily generous
 * constant) rather than silently tolerated. {@link
 * #pensionHeavyScenario_surplusFundedTaxSliceExclusion_engagesWithBoundedDeviation()} proves the
 * exclusion is non-vacuous: at least one scenario-year genuinely needs the wider tolerance (the
 * flat-$1 formula alone would fail there).
 *
 * <h2>Invariant 2 — tax reconciliation (T18a-5b, closed out by T23 item 1)</h2>
 * {@code nz(federalTax) + nz(stateTax) == nz(taxLiability)} to the cent, UNCONDITIONALLY, every
 * year. Self-employment tax and the early-withdrawal penalty are already folded into
 * {@code federalTax} by {@code RetirementTaxAnnotator} (see its javadoc) whenever it runs
 * ({@code taxLiability > 0}); this class does not need to add them separately. When
 * {@code taxLiability} is null (nets to zero — the DTO's "positive value or null" convention),
 * {@code federalTax} can still be legitimately non-null (a literal {@code $0}, e.g. a retiree
 * whose withdrawal is fully absorbed by the deduction) — the SAME zero shown two ways, not a
 * defect. T23 item 1 removed this invariant's original workaround (skip years with a null/non-
 * positive {@code taxLiability}, added because {@code MultiPoolYearDtoBuilder} could surface a
 * genuinely STALE breakdown — one that DISAGREED with {@code taxLiability} — in that window; see
 * {@code MultiPoolYearDtoBuilder#build}'s javadoc for the fix) by fixing the production code
 * instead: the breakdown is now suppressed whenever (and only whenever) it disagrees with
 * {@code taxLiability}, so this identity holds unconditionally with no per-year exception.
 *
 * <h2>Invariant 3 — direction properties</h2>
 * Each base scenario is re-run three ways: {@code fee_rate} 0 -&gt; 0.01 (strictly lower final
 * balance — a uniform per-account return drag); {@code dividend_yield} 0 -&gt; 0.03 (retirement-
 * year {@code capitalGainsTax} non-decreasing, final balance non-increasing — realizing more
 * taxable dividend income can only raise the tax bill, which can only reduce what remains);
 * {@code include_depression_years} false -&gt; true (byte-identical {@code yearlyData} JSON). The
 * third is a STRUCTURAL no-op in this suite, not a coincidence: every fixture account carries an
 * {@code expectedReturn} OVERRIDE (returns are never allocation-derived — the audit-C1
 * bond60/40+traditional mix's allocation drives only the taxable yield SPLIT, not its return) and
 * every engine instance is built
 * with a {@code null} {@code CapitalMarketAssumptionsProvider} (matching every sibling engine test
 * in this module) — {@code DeterministicProjectionEngine#buildPoolStrategy} short-circuits to an
 * empty {@code geoMeans} map whenever the provider is null, so the {@code
 * include_depression_years} predicate is parsed but never consulted. This "provider predicate
 * pin" documents that structural fact rather than exercising the CMA provider's own windowing
 * (out of scope here — that lives with {@code CapitalMarketAssumptionsProvider}'s own tests).
 *
 * <h2>Invariant 4 — RMD correctness</h2>
 * For every scenario-year at or past {@link RmdCalculator#rmdStartAge}: {@code rmdAmount} equals
 * the {@link RmdCalculator#distributionPeriod} oracle applied to the PRIOR year's ending
 * traditional balance (bit-exact, same HALF_UP/scale-4 rounding as production), is strictly
 * positive, and the traditional pool decreases by at least the RMD net of that year's own
 * traditional growth (a lower bound — further traditional draws under {@code dynamic_sequencing}
 * or a spend draw only decrease the balance further, which does not violate the bound).
 */
class EngineInvariantsTest {

    private static final BigDecimal ONE_DOLLAR = BigDecimal.ONE;
    private static final int REFERENCE_YEAR = 2025;
    private static final LocalDate ALWAYS_RETIRED_DATE = LocalDate.of(2000, 1, 1);
    private static final int END_AGE = 95;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private TaxBracketRepository taxBracketRepository;
    private StandardDeductionRepository standardDeductionRepository;
    private LtcgBracketRepository ltcgBracketRepository;
    private DeterministicProjectionEngine engine;

    @BeforeEach
    void setUp() {
        taxBracketRepository = mock(TaxBracketRepository.class);
        standardDeductionRepository = mock(StandardDeductionRepository.class);
        ltcgBracketRepository = mock(LtcgBracketRepository.class);
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        stubSingle2025Ltcg(ltcgBracketRepository);
        // Household matrix (task 10): the household cases file MFJ until the first-death filing
        // flip. Lenient stubs -- the single-person matrix never queries the MFJ tables.
        stubMfj2025(taxBracketRepository, standardDeductionRepository);
        stubMfj2025Ltcg(ltcgBracketRepository);

        var federalTaxCalculator = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        var capitalGainsTaxCalculator = new CapitalGainsTaxCalculator(ltcgBracketRepository);
        // 4-arg test-friendly constructor: no state calculator, no IRMAA, no meter registry, and
        // (crucially for Invariant 3's depression-years pin) a null CapitalMarketAssumptionsProvider.
        engine = new DeterministicProjectionEngine(federalTaxCalculator, null, capitalGainsTaxCalculator, null);
    }

    // === Scenario matrix ===

    record ScenarioCase(String label, String accountMix, String withdrawalOrder, String incomeShape,
                         int birthYear, boolean hasTraditional) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<ScenarioCase> scenarios() {
        List<ScenarioCase> cases = new ArrayList<>();
        // "bond60/40+traditional" (audit C1): a 60/40 us_stock/bond taxable account, so every
        // invariant -- especially the tax-reconciliation identity -- runs through the REAL engine
        // with nonzero ordinary-interest income (the matrix's paramsJson never sets
        // interest_yield, so the 0.04 default is live). The other three mixes are all ALL_US
        // (interest income identically zero), which would leave the annotator's interest wiring
        // pinned only by the mocked RetirementTaxAnnotatorTest.
        for (String mix : List.of("taxable-only", "taxable+traditional", "all-three",
                "bond60/40+traditional")) {
            // T18b: birth years chosen so RMD age falls inside the horizon for the
            // traditional-containing mixes, on both sides of the SECURE 2.0 threshold
            // (birth year 1960) -- 1955 -> RMD age 73, 1965 -> RMD age 75. taxable-only never
            // forces an RMD (no traditional balance) so its birth year is immaterial.
            int birthYear = switch (mix) {
                case "taxable+traditional" -> 1955;
                case "bond60/40+traditional" -> 1955;
                case "all-three" -> 1965;
                default -> 1965;
            };
            boolean hasTraditional = !"taxable-only".equals(mix);
            for (String order : List.of("taxable_first", "dynamic_sequencing")) {
                for (String incomeShape : List.of("none", "pension-heavy", "ss-typed")) {
                    cases.add(new ScenarioCase(mix + " | " + order + " | " + incomeShape,
                            mix, order, incomeShape, birthYear, hasTraditional));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void invariantsHoldAcrossScenarioMatrix(ScenarioCase sc) {
        var baseInput = buildInput(sc, paramsJson(sc, BigDecimal.ZERO, BigDecimal.ZERO, false));
        var baseResult = engine.run(baseInput);

        assertBalanceIdentity(sc.label(), baseResult.yearlyData());
        assertTaxReconciliation(sc.label(), baseResult.yearlyData());
        if (sc.hasTraditional()) {
            assertRmdInvariant(sc, baseResult.yearlyData());
        } else {
            assertNoRmdEver(baseResult.yearlyData());
        }

        assertFeeRateDirection(sc, baseResult);
        assertDividendYieldDirection(sc, baseResult);
        assertDepressionYearsByteIdentical(sc, baseResult);
    }

    // === Invariant 1: balance identity ===

    private void assertBalanceIdentity(String label, List<ProjectionYearDto> years) {
        for (var y : years) {
            BigDecimal expectedEnd = nz(y.startBalance())
                    .add(nz(y.contributions()))
                    .add(nz(y.growth()))
                    .add(nz(y.surplusReinvested()))
                    .add(nz(y.rmdAmount()))
                    .subtract(nz(y.withdrawals()))
                    .subtract(nz(y.taxLiability()));

            BigDecimal grossSurplusApprox = nz(y.incomeStreamsTotal())
                    .subtract(nz(y.essentialExpenses()))
                    .subtract(nz(y.discretionaryExpenses()));
            BigDecimal tolerance = grossSurplusApprox.max(BigDecimal.ZERO).add(ONE_DOLLAR);

            assertThat(nz(y.endBalance()).subtract(expectedEnd).abs())
                    .as("[%s] balance identity year %d (age %d)", label, y.year(), y.age())
                    .isLessThanOrEqualTo(tolerance);
        }
    }

    // === Invariant 2: tax reconciliation (T18a-5b) ===

    private void assertTaxReconciliation(String label, List<ProjectionYearDto> years) {
        for (var y : years) {
            // T23 item 1: MultiPoolYearDtoBuilder now suppresses the pool's raw federalTax/stateTax
            // breakdown whenever it genuinely DISAGREES with taxLiability (a stale breakdown -- see
            // its javadoc), so every remaining breakdown is trustworthy by construction. federalTax
            // can still legitimately be non-null (even exactly $0) in a year whose taxLiability nets
            // to null via the DTO's "positive value or null" convention -- that is the SAME zero
            // represented two different ways, not a defect (see MultiPoolYearDtoBuilderTest's
            // itemized-deduction pin). Comparing both sides null-safe (nz()), with no skip/continue,
            // exercises this identity unconditionally on every year instead of the old workaround
            // that special-cased away the stale-breakdown gap this class discovered (T18b).
            BigDecimal total = nz(y.federalTax()).add(nz(y.stateTax()));
            assertThat(total)
                    .as("[%s] federalTax + stateTax == taxLiability year %d", label, y.year())
                    .isCloseTo(nz(y.taxLiability()), offset(bd("0.01")));
        }
    }

    // === Invariant 3: direction properties ===

    private void assertFeeRateDirection(ScenarioCase sc, ProjectionResultResponse base) {
        var feeInput = buildInput(sc, paramsJson(sc, bd("0.01"), BigDecimal.ZERO, false));
        var feeResult = engine.run(feeInput);

        assertThat(feeResult.finalBalance())
                .as("[%s] fee_rate 0->0.01 must strictly lower the final balance", sc.label())
                .isLessThan(base.finalBalance());
    }

    private void assertDividendYieldDirection(ScenarioCase sc, ProjectionResultResponse base) {
        var divInput = buildInput(sc, paramsJson(sc, BigDecimal.ZERO, bd("0.03"), false));
        var divResult = engine.run(divInput);

        assertThat(divResult.finalBalance())
                .as("[%s] dividend_yield 0->0.03 must not increase the final balance", sc.label())
                .isLessThanOrEqualTo(base.finalBalance());

        var baseYears = base.yearlyData();
        var divYears = divResult.yearlyData();
        for (int i = 0; i < baseYears.size(); i++) {
            var baseY = baseYears.get(i);
            if (!baseY.retired()) {
                continue;
            }
            assertThat(nz(divYears.get(i).capitalGainsTax()))
                    .as("[%s] dividend_yield 0->0.03 must not decrease retirement-year capitalGainsTax, year %d",
                            sc.label(), baseY.year())
                    .isGreaterThanOrEqualTo(nz(baseY.capitalGainsTax()));
        }
    }

    private void assertDepressionYearsByteIdentical(ScenarioCase sc, ProjectionResultResponse base) {
        var depInput = buildInput(sc, paramsJson(sc, BigDecimal.ZERO, BigDecimal.ZERO, true));
        var depResult = engine.run(depInput);

        String baseJson = MAPPER.writeValueAsString(base.yearlyData());
        String depJson = MAPPER.writeValueAsString(depResult.yearlyData());
        assertThat(depJson)
                .as("[%s] include_depression_years toggle must be byte-identical (no CMA provider wired)",
                        sc.label())
                .isEqualTo(baseJson);
        assertThat(depResult.finalBalance())
                .as("[%s] include_depression_years toggle must not move finalBalance", sc.label())
                .isEqualByComparingTo(base.finalBalance());
    }

    // === Invariant 4: RMD correctness ===

    private void assertRmdInvariant(ScenarioCase sc, List<ProjectionYearDto> years) {
        int rmdStartAge = RmdCalculator.rmdStartAge(sc.birthYear());
        BigDecimal priorTraditional = initialTraditionalBalance(sc);

        for (var y : years) {
            BigDecimal traditionalBalance = nz(y.traditionalBalance());
            if (y.age() >= rmdStartAge && priorTraditional.compareTo(BigDecimal.ZERO) > 0) {
                double divisor = RmdCalculator.distributionPeriod(y.age());
                if (divisor > 0) {
                    BigDecimal expectedRmd = priorTraditional.divide(
                            BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);

                    assertThat(expectedRmd)
                            .as("[%s] RMD oracle positive year %d (age %d)", sc.label(), y.year(), y.age())
                            .isGreaterThan(BigDecimal.ZERO);
                    assertThat(nz(y.rmdAmount()))
                            .as("[%s] rmdAmount matches RmdCalculator oracle year %d", sc.label(), y.year())
                            .isEqualByComparingTo(expectedRmd);

                    BigDecimal maxAllowedTraditional = priorTraditional
                            .add(nz(y.traditionalGrowth()))
                            .subtract(expectedRmd)
                            .add(ONE_DOLLAR);
                    assertThat(traditionalBalance)
                            .as("[%s] traditional decreases by >= RMD (net of growth) year %d",
                                    sc.label(), y.year())
                            .isLessThanOrEqualTo(maxAllowedTraditional);
                }
            }
            priorTraditional = traditionalBalance;
        }
    }

    private void assertNoRmdEver(List<ProjectionYearDto> years) {
        for (var y : years) {
            assertThat(y.rmdAmount()).as("taxable-only mix never forces an RMD, year %d", y.year()).isNull();
        }
    }

    // === Non-vacuity pin: the surplus-funded-tax-slice exclusion genuinely engages ===

    /**
     * Proves Invariant 1's wider tolerance is load-bearing, not decorative: at least one
     * scenario-year has a genuine active-income surplus (pension exceeds spending), AND the
     * SIMPLE identity (without the {@code grossSurplusApprox} allowance) shows a real deviation
     * there bounded by that allowance -- so the exclusion is exercised, not vacuously always zero.
     */
    @Test
    void pensionHeavyScenario_surplusFundedTaxSliceExclusion_engagesWithBoundedDeviation() {
        var sc = new ScenarioCase("all-three | taxable_first | pension-heavy",
                "all-three", "taxable_first", "pension-heavy", 1965, true);
        var input = buildInput(sc, paramsJson(sc, BigDecimal.ZERO, BigDecimal.ZERO, false));
        var result = engine.run(input);

        boolean exclusionEngaged = false;
        for (var y : result.yearlyData()) {
            BigDecimal grossSurplusApprox = nz(y.incomeStreamsTotal())
                    .subtract(nz(y.essentialExpenses()))
                    .subtract(nz(y.discretionaryExpenses()));
            if (grossSurplusApprox.compareTo(ONE_DOLLAR) <= 0) {
                continue;
            }
            exclusionEngaged = true;

            BigDecimal expectedEndNoAllowance = nz(y.startBalance())
                    .add(nz(y.contributions()))
                    .add(nz(y.growth()))
                    .add(nz(y.surplusReinvested()))
                    .add(nz(y.rmdAmount()))
                    .subtract(nz(y.withdrawals()))
                    .subtract(nz(y.taxLiability()));
            BigDecimal rawResidual = nz(y.endBalance()).subtract(expectedEndNoAllowance).abs();

            assertThat(rawResidual)
                    .as("year %d must show a measurable surplus-funded deviation beyond the flat $1 base"
                            + " tolerance -- otherwise the exclusion would be vacuous", y.year())
                    .isGreaterThan(ONE_DOLLAR);
            assertThat(rawResidual)
                    .as("year %d deviation must stay within the documented grossSurplus bound", y.year())
                    .isLessThanOrEqualTo(grossSurplusApprox.add(ONE_DOLLAR));
        }

        assertThat(exclusionEngaged)
                .as("pension-heavy fixture must actually trigger the surplus-funded-tax-slice exclusion "
                        + "in at least one year")
                .isTrue();
    }

    // === Audit C1: interest_yield direction properties ===

    /**
     * A 60/40 us_stock/bond taxable account (NO traditional pool -- deliberately: an RMD-forced
     * traditional distribution in later years would push Social Security's provisional income
     * straight to its 85%-of-benefit cap, saturating the very effect this test is isolating) plus
     * a real Social Security income source: interest income is ordinary AGI, so raising {@code
     * interest_yield} must (a) raise the AGGREGATE federal tax paid across the horizon, (b) raise
     * the AGGREGATE Social Security taxable amount across the horizon (the provisional-income
     * effect audit C1 threads through {@code realizedPortfolioTaxable}), and (c) not increase the
     * final balance. Aggregate (summed) rather than strict per-year monotonicity is the right
     * granularity for BOTH (a) and (b): a materially higher EARLY-year tax bill draws the taxable
     * pool down by a few extra dollars, which can shift a LATER year's realized gain/dividend (and
     * thus that year's own tax AND its own provisional-income base) by cents in either direction
     * -- a real, harmless second-order effect the direction property must not choke on.
     */
    @Test
    void interestYieldDirection_bondAllocatedTaxableAccount_raisesTaxLowersBalance() {
        List<ProjectionAccountInput> accounts = List.of(bondAllocatedAcct("1200000.0000", "0.0500"));
        var incomeSources = incomeSourcesFor("ss-typed");
        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), null);

        var baseInput = createInput(ALWAYS_RETIRED_DATE, END_AGE, bd("0.02"),
                interestYieldParamsJson(BigDecimal.ZERO), accounts, spending, REFERENCE_YEAR, incomeSources);
        var interestInput = createInput(ALWAYS_RETIRED_DATE, END_AGE, bd("0.02"),
                interestYieldParamsJson(bd("0.04")), accounts, spending, REFERENCE_YEAR, incomeSources);

        var baseResult = engine.run(baseInput);
        var interestResult = engine.run(interestInput);

        assertThat(interestResult.finalBalance())
                .as("interest_yield 0->0.04 on a bond-allocated account must not increase the final balance")
                .isLessThanOrEqualTo(baseResult.finalBalance());

        assertThat(sumField(interestResult.yearlyData(), ProjectionYearDto::federalTax))
                .as("interest_yield 0->0.04 must raise the AGGREGATE federal tax paid across the horizon")
                .isGreaterThan(sumField(baseResult.yearlyData(), ProjectionYearDto::federalTax));

        assertThat(sumField(interestResult.yearlyData(), ProjectionYearDto::socialSecurityTaxable))
                .as("interest_yield 0->0.04 must raise the AGGREGATE Social Security taxable amount "
                        + "across the horizon (provisional-income effect)")
                .isGreaterThan(sumField(baseResult.yearlyData(), ProjectionYearDto::socialSecurityTaxable));
    }

    private static BigDecimal sumField(List<ProjectionYearDto> years,
                                        java.util.function.Function<ProjectionYearDto, BigDecimal> field) {
        BigDecimal total = BigDecimal.ZERO;
        for (var y : years) {
            total = total.add(nz(field.apply(y)));
        }
        return total;
    }

    /**
     * The byte-identical backward-compat anchor (audit C1): an ALL_US taxable account (equity
     * share 1.0, bond share 0.0) must be COMPLETELY invariant to interest_yield -- the bond+cash
     * sleeve that rate taxes doesn't exist for this account, mirroring
     * {@link #assertDepressionYearsByteIdentical}'s no-op-toggle pattern.
     */
    @Test
    void interestYieldDirection_allUsScenario_invariantToToggle() {
        var sc = new ScenarioCase("taxable-only | taxable_first | none",
                "taxable-only", "taxable_first", "none", 1965, false);
        var baseInput = buildInput(sc, interestYieldParamsJson(BigDecimal.ZERO));
        var interestInput = buildInput(sc, interestYieldParamsJson(bd("0.04")));

        var baseResult = engine.run(baseInput);
        var interestResult = engine.run(interestInput);

        String baseJson = MAPPER.writeValueAsString(baseResult.yearlyData());
        String interestJson = MAPPER.writeValueAsString(interestResult.yearlyData());
        assertThat(interestJson)
                .as("interest_yield toggle must be byte-identical for an ALL_US account (no bond/cash sleeve)")
                .isEqualTo(baseJson);
    }

    private String interestYieldParamsJson(BigDecimal interestYield) {
        return "{\"birth_year\": 1965, \"filing_status\": \"single\", "
                + "\"withdrawal_order\": \"taxable_first\", \"dividend_yield\": 0, "
                + "\"interest_yield\": " + interestYield + "}";
    }

    private static HypotheticalAccountInput bondAllocatedAcct(String balance, String expectedReturn) {
        var allocation = new AssetAllocation(Map.of(
                AssetClass.US_STOCK, bd("0.6"), AssetClass.BOND, bd("0.4")));
        return new HypotheticalAccountInput(bd(balance), BigDecimal.ZERO, allocation,
                Optional.of(bd(expectedReturn)), "taxable");
    }

    // === Household matrix (household task 10): the same invariants across the first-death
    // transition boundary ===

    /**
     * Household case shape: an age-gap couple (births 1955/1965 — RMD start ages on BOTH sides of
     * the SECURE-2.0 threshold: 73 in 2028 for the primary, 75 in 2040 for the spouse), owned
     * accounts across all five pools (joint taxable with embedded gain, trad-P, trad-S, roth-P,
     * roth-S), an SS pair plus a 50%-survivor pension, and a first death at the 2042 boundary
     * (primary dies at 87). The spouse's death (2060) lands beyond the 2050 horizon, so every
     * invariant runs the FULL horizon across the transition: the balance identity (the spousal
     * rollover and basis step-up are value-neutral, so no new identity term is needed — pinned
     * additionally by {@link #assertYearChainContinuity} at the boundary), the unconditional tax
     * reconciliation through the MFJ-to-single flip, the three direction properties, and — for the
     * {@code taxable_first} case, where portfolio draws never touch the traditional pools — a
     * TWO-STREAM per-owner RMD oracle built from single-owner control runs (see
     * {@link #assertTwoStreamRmdOracle}).
     */
    record HouseholdCase(String label, String withdrawalOrder, boolean communityProperty) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<HouseholdCase> householdScenarios() {
        return Stream.of(
                new HouseholdCase("household | taxable_first | ss-pair+pension", "taxable_first", false),
                new HouseholdCase("household | dynamic_sequencing | community-property",
                        "dynamic_sequencing", true));
    }

    private static final int HH_PRIMARY_BIRTH = 1955;
    private static final int HH_SPOUSE_BIRTH = 1965;
    /** First-death calendar year: the primary dies at 87 (1955 + 87). */
    private static final int HH_TRANSITION_YEAR = 2042;
    private static final String HH_TRAD_P = "600000.0000";
    private static final String HH_TRAD_S = "500000.0000";
    // Fixed ids for the same reason as PENSION_SOURCE_ID above: the depression-years byte-compare
    // serializes incomeBySource keyed by source id.
    private static final UUID HH_SS_PRIMARY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID HH_SS_SPOUSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final UUID HH_PENSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    private static final UUID HH_SCENARIO_ID = UUID.nameUUIDFromBytes("household-invariants".getBytes());

    @ParameterizedTest(name = "{0}")
    @MethodSource("householdScenarios")
    void invariantsHoldAcrossHouseholdTransition(HouseholdCase hc) {
        var base = engine.run(buildHouseholdInput(hc, BigDecimal.ZERO, BigDecimal.ZERO, false,
                HH_TRAD_P, HH_TRAD_S));
        var years = base.yearlyData();

        // The transition fires inside the horizon; the second death (2060) is beyond it, so the
        // projection runs the full horizon (no truncation) and both phases are exercised.
        assertThat(years.getFirst().year()).isEqualTo(REFERENCE_YEAR);
        assertThat(years.getLast().year()).isEqualTo(HH_PRIMARY_BIRTH + END_AGE - 1);

        assertBalanceIdentity(hc.label(), years);
        assertTaxReconciliation(hc.label(), years);
        assertYearChainContinuity(hc.label(), years);

        assertHouseholdFeeRateDirection(hc, base);
        assertHouseholdDividendYieldDirection(hc, base);
        assertHouseholdDepressionYearsByteIdentical(hc, base);

        if ("taxable_first".equals(hc.withdrawalOrder())) {
            assertTwoStreamRmdOracle(hc, base);
        }
    }

    /**
     * Rollover + step-up conservation: the transition is an internal reshuffle (deceased's
     * trad/roth move to the survivor; the joint-taxable BASIS steps up, never the value), so the
     * year chain must stay perfectly continuous across the boundary — year Y's opening balance is
     * exactly year Y-1's close, with no value created or destroyed. Asserted over EVERY adjacent
     * pair so the transition year gets no special-case exemption.
     */
    private void assertYearChainContinuity(String label, List<ProjectionYearDto> years) {
        for (int i = 1; i < years.size(); i++) {
            assertThat(years.get(i).startBalance())
                    .as("[%s] startBalance(%d) == endBalance(%d) — transition conserves value",
                            label, years.get(i).year(), years.get(i - 1).year())
                    .isEqualByComparingTo(years.get(i - 1).endBalance());
        }
    }

    /**
     * Two-stream per-owner RMD oracle (spec §3): each owner's traditional pool RMDs at that
     * owner's own SECURE-2.0 age from that owner's own prior-year balance. Because the DTO only
     * exposes the SUMMED traditional balance, per-owner streams are isolated via two single-owner
     * CONTROL runs (the other owner's traditional account zeroed). Under {@code taxable_first}
     * with a never-depleting taxable pool, no draw or tax cascade ever touches a traditional pool,
     * so each control run's stream is bit-identical to that owner's stream inside the full run:
     * <ul>
     *   <li>each control run's stream must match the {@link RmdCalculator} oracle applied to its
     *       own prior-year DTO traditional balance — starting exactly at the OWNER's start year
     *       (2028 at 73 for the primary born 1955; 2040 at 75 for the spouse born 1965 — the
     *       age-gap correctness this feature exists for), and switching to the SURVIVOR's
     *       age/divisor from the post-rollover year (2043) onward;</li>
     *   <li>the full run's {@code rmd_amount} must equal the SUM of the two control streams —
     *       bit-exact through the transition year (two independently-rounded streams on both
     *       sides), and within a rounding tick after the rollover merges the pools (one
     *       merged-balance division versus the controls' two — HALF_UP drift is bounded by one
     *       0.0001 tick per divide per year).</li>
     * </ul>
     */
    private void assertTwoStreamRmdOracle(HouseholdCase hc, ProjectionResultResponse full) {
        var primaryOnly = engine.run(buildHouseholdInput(hc, BigDecimal.ZERO, BigDecimal.ZERO, false,
                HH_TRAD_P, "0.0000"));
        var spouseOnly = engine.run(buildHouseholdInput(hc, BigDecimal.ZERO, BigDecimal.ZERO, false,
                "0.0000", HH_TRAD_S));

        assertSingleOwnerStreamMatchesOracle("primary-only control", primaryOnly, HH_PRIMARY_BIRTH, 2028);
        assertSingleOwnerStreamMatchesOracle("spouse-only control", spouseOnly, HH_SPOUSE_BIRTH, 2040);

        for (var y : full.yearlyData()) {
            BigDecimal sum = rmdOf(primaryOnly, y.year()).add(rmdOf(spouseOnly, y.year()));
            if (y.year() <= HH_TRANSITION_YEAR) {
                assertThat(nz(y.rmdAmount()))
                        .as("[%s] rmd_amount == primary stream + spouse stream (bit-exact), year %d",
                                hc.label(), y.year())
                        .isEqualByComparingTo(sum);
            } else {
                assertThat(nz(y.rmdAmount()))
                        .as("[%s] merged survivor stream == sum of control streams (rounding tick), year %d",
                                hc.label(), y.year())
                        .isCloseTo(sum, offset(bd("0.001")));
            }
        }
    }

    private void assertSingleOwnerStreamMatchesOracle(String label, ProjectionResultResponse run,
                                                      int ownerBirthYear, int expectedFirstRmdYear) {
        BigDecimal priorTraditional = null;
        for (var y : run.yearlyData()) {
            if (priorTraditional != null && priorTraditional.compareTo(BigDecimal.ZERO) > 0) {
                if (y.year() < expectedFirstRmdYear) {
                    assertThat(y.rmdAmount())
                            .as("[%s] no RMD before the owner's own start year, year %d", label, y.year())
                            .isNull();
                } else {
                    // Post-rollover (2043+) the surviving spouse's age/table governs the inherited
                    // pool — including in the primary-only control, whose pool the survivor now owns.
                    int streamAge = y.year() <= HH_TRANSITION_YEAR
                            ? y.year() - ownerBirthYear
                            : y.year() - HH_SPOUSE_BIRTH;
                    BigDecimal expected = priorTraditional.divide(
                            BigDecimal.valueOf(RmdCalculator.distributionPeriod(streamAge)),
                            4, RoundingMode.HALF_UP);
                    assertThat(nz(y.rmdAmount()))
                            .as("[%s] stream matches RmdCalculator oracle (age %d), year %d",
                                    label, streamAge, y.year())
                            .isEqualByComparingTo(expected);
                }
            }
            priorTraditional = nz(y.traditionalBalance());
        }
    }

    private static BigDecimal rmdOf(ProjectionResultResponse run, int year) {
        return run.yearlyData().stream()
                .filter(y -> y.year() == year)
                .findFirst()
                .map(y -> nz(y.rmdAmount()))
                .orElse(BigDecimal.ZERO);
    }

    // Direction properties over the household path (same claims as the single-person matrix).

    private void assertHouseholdFeeRateDirection(HouseholdCase hc, ProjectionResultResponse base) {
        var feeResult = engine.run(buildHouseholdInput(hc, bd("0.01"), BigDecimal.ZERO, false,
                HH_TRAD_P, HH_TRAD_S));
        assertThat(feeResult.finalBalance())
                .as("[%s] fee_rate 0->0.01 must strictly lower the final balance", hc.label())
                .isLessThan(base.finalBalance());
    }

    private void assertHouseholdDividendYieldDirection(HouseholdCase hc, ProjectionResultResponse base) {
        var divResult = engine.run(buildHouseholdInput(hc, BigDecimal.ZERO, bd("0.03"), false,
                HH_TRAD_P, HH_TRAD_S));
        assertThat(divResult.finalBalance())
                .as("[%s] dividend_yield 0->0.03 must not increase the final balance", hc.label())
                .isLessThanOrEqualTo(base.finalBalance());
        var baseYears = base.yearlyData();
        var divYears = divResult.yearlyData();
        for (int i = 0; i < baseYears.size(); i++) {
            if (!baseYears.get(i).retired()) {
                continue;
            }
            assertThat(nz(divYears.get(i).capitalGainsTax()))
                    .as("[%s] dividend_yield 0->0.03 must not decrease capitalGainsTax, year %d",
                            hc.label(), baseYears.get(i).year())
                    .isGreaterThanOrEqualTo(nz(baseYears.get(i).capitalGainsTax()));
        }
    }

    private void assertHouseholdDepressionYearsByteIdentical(HouseholdCase hc, ProjectionResultResponse base) {
        var depResult = engine.run(buildHouseholdInput(hc, BigDecimal.ZERO, BigDecimal.ZERO, true,
                HH_TRAD_P, HH_TRAD_S));
        assertThat(MAPPER.writeValueAsString(depResult.yearlyData()))
                .as("[%s] include_depression_years toggle must be byte-identical (no CMA provider wired)",
                        hc.label())
                .isEqualTo(MAPPER.writeValueAsString(base.yearlyData()));
    }

    private ProjectionInput buildHouseholdInput(HouseholdCase hc, BigDecimal feeRate, BigDecimal dividendYield,
                                                boolean includeDepressionYears,
                                                String tradPrimaryBalance, String tradSpouseBalance) {
        // Second death 2060 (1965 + 95) is beyond the 2050 horizon => transition only, no truncation.
        var household = HouseholdContext.of(HH_PRIMARY_BIRTH, 87, HH_SPOUSE_BIRTH, 95,
                HH_PRIMARY_BIRTH + END_AGE);
        List<ProjectionAccountInput> accounts = List.of(
                hhAcct("1400000.0000", "900000.0000", "0.0500", "taxable", "joint"),
                hhAcct(tradPrimaryBalance, tradPrimaryBalance, "0.0600", "traditional", "primary"),
                hhAcct(tradSpouseBalance, tradSpouseBalance, "0.0600", "traditional", "spouse"),
                hhAcct("200000.0000", "200000.0000", "0.0600", "roth", "primary"),
                hhAcct("100000.0000", "100000.0000", "0.0600", "roth", "spouse"));
        // Deficit in every retirement year on BOTH sides of the transition (income 47k < 50k
        // spending both alive; 20k kept SS + 7.5k half-pension = 27.5k < 37.5k scaled after), so
        // the balance identity runs at its tight flat-$1 tolerance throughout.
        var incomeSources = List.of(
                hhSource(HH_SS_PRIMARY_ID, "SS-P", IncomeSourceType.SOCIAL_SECURITY, "20000", "primary", "1"),
                hhSource(HH_SS_SPOUSE_ID, "SS-S", IncomeSourceType.SOCIAL_SECURITY, "12000", "spouse", "1"),
                hhSource(HH_PENSION_ID, "Pension", IncomeSourceType.PENSION, "15000", "primary", "0.5"));
        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), null);
        var sb = new StringBuilder("{");
        sb.append("\"birth_year\": ").append(HH_PRIMARY_BIRTH).append(", ");
        sb.append("\"filing_status\": \"married_filing_jointly\", ");
        sb.append("\"withdrawal_order\": \"").append(hc.withdrawalOrder()).append("\", ");
        if ("dynamic_sequencing".equals(hc.withdrawalOrder())) {
            sb.append("\"dynamic_sequencing_bracket_rate\": 0.12, ");
        }
        sb.append("\"survivor_spending_factor\": 0.75, ");
        sb.append("\"community_property\": ").append(hc.communityProperty()).append(", ");
        sb.append("\"fee_rate\": ").append(feeRate).append(", ");
        sb.append("\"dividend_yield\": ").append(dividendYield).append(", ");
        sb.append("\"include_depression_years\": ").append(includeDepressionYears);
        sb.append('}');
        return new ProjectionInput(HH_SCENARIO_ID, "Household invariants", ALWAYS_RETIRED_DATE, END_AGE,
                bd("0.02"), sb.toString(), accounts, spending, REFERENCE_YEAR, incomeSources,
                null, List.of(), household);
    }

    private static HypotheticalAccountInput hhAcct(String balance, String costBasis, String expectedReturn,
                                                   String type, String owner) {
        return new HypotheticalAccountInput(bd(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.of(bd(expectedReturn)), bd(costBasis), type, owner);
    }

    private static ProjectionIncomeSourceInput hhSource(UUID id, String name, IncomeSourceType type,
                                                        String amount, String owner, String survivorPercent) {
        return new ProjectionIncomeSourceInput(id, name, type, bd(amount), 62, null, bd("0.02"),
                false, "taxable", null, null, null, null, null, null, owner, bd(survivorPercent));
    }

    // === Fixture construction ===

    private ProjectionInput buildInput(ScenarioCase sc, String paramsJson) {
        List<ProjectionAccountInput> accounts = accountsFor(sc.accountMix());
        List<ProjectionIncomeSourceInput> incomeSources = incomeSourcesFor(sc.incomeShape());
        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), null);
        return createInput(ALWAYS_RETIRED_DATE, END_AGE, bd("0.02"), paramsJson,
                accounts, spending, REFERENCE_YEAR, incomeSources);
    }

    private String paramsJson(ScenarioCase sc, BigDecimal feeRate, BigDecimal dividendYield,
                               boolean includeDepressionYears) {
        var sb = new StringBuilder("{");
        sb.append("\"birth_year\": ").append(sc.birthYear()).append(", ");
        sb.append("\"filing_status\": \"single\", ");
        sb.append("\"withdrawal_order\": \"").append(sc.withdrawalOrder()).append("\", ");
        if ("dynamic_sequencing".equals(sc.withdrawalOrder())) {
            sb.append("\"dynamic_sequencing_bracket_rate\": 0.12, ");
        }
        sb.append("\"fee_rate\": ").append(feeRate).append(", ");
        sb.append("\"dividend_yield\": ").append(dividendYield).append(", ");
        sb.append("\"include_depression_years\": ").append(includeDepressionYears);
        sb.append('}');
        return sb.toString();
    }

    // Balances are sized generously (vs. the $40k/$50k essential+discretionary spending profile)
    // so the portfolio never fully depletes across the ~30-35 year horizon in ANY scenario shape
    // -- a portfolio that floors at zero in both the base AND fee_rate/dividend_yield variant runs
    // would mask Invariant 3's direction properties (both final balances would tie at 0.0000).
    private List<ProjectionAccountInput> accountsFor(String mix) {
        return switch (mix) {
            case "taxable-only" -> List.of(acct("2000000.0000", "0", "0.0500", "taxable"));
            case "taxable+traditional" -> List.of(
                    acct("400000.0000", "0", "0.0500", "taxable"),
                    acct("900000.0000", "0", "0.0600", "traditional"));
            case "all-three" -> List.of(
                    acct("300000.0000", "0", "0.0500", "taxable"),
                    acct("800000.0000", "0", "0.0600", "traditional"),
                    acct("300000.0000", "0", "0.0600", "roth"));
            // Audit C1: same balances as taxable+traditional, but the taxable account carries a
            // 60/40 us_stock/bond allocation (return still from the SAME 5% expectedReturn
            // override -- allocation drives only the yield split), so ~40% * balance * 0.04 of
            // ordinary-interest income flows through every retirement year.
            case "bond60/40+traditional" -> List.of(
                    bondAllocatedAcct("400000.0000", "0.0500"),
                    acct("900000.0000", "0", "0.0600", "traditional"));
            default -> throw new IllegalArgumentException("Unknown mix: " + mix);
        };
    }

    private BigDecimal initialTraditionalBalance(ScenarioCase sc) {
        return accountsFor(sc.accountMix()).stream()
                .filter(a -> a.poolType() == PoolType.TRADITIONAL)
                .map(ProjectionAccountInput::initialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Fixed (not random) UUIDs: buildInput() is called multiple times per scenario (base +
    // fee_rate/dividend_yield/include_depression_years variants), and Invariant 3's
    // depression-years check compares serialized JSON byte-for-byte, including the
    // incomeBySource map keyed by source id -- a fresh UUID per call would make that comparison
    // spuriously fail on the id alone, even though every dollar value is identical.
    private static final UUID PENSION_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SS_SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private List<ProjectionIncomeSourceInput> incomeSourcesFor(String shape) {
        return switch (shape) {
            case "none" -> List.of();
            // Pension exceeds the $50,000 spending profile in every active year, deliberately
            // engaging the RetirementWithdrawalProcessor surplus-funded-tax-slice branch (Invariant
            // 1's first documented exclusion) -- see the non-vacuity pin test above.
            case "pension-heavy" -> List.of(new ProjectionIncomeSourceInput(
                    PENSION_SOURCE_ID, "Pension", IncomeSourceType.PENSION,
                    bd("70000"), 50, null, bd("0.02"), false, "taxable",
                    null, null, null, null, null, null));
            // Real Social Security provisional-income taxability + the audit-B2 convergence loop.
            case "ss-typed" -> List.of(new ProjectionIncomeSourceInput(
                    SS_SOURCE_ID, "Social Security", IncomeSourceType.SOCIAL_SECURITY,
                    bd("24000"), 67, null, bd("0"), false, "taxable",
                    null, null, null, null, null, null));
            default -> throw new IllegalArgumentException("Unknown income shape: " + shape);
        };
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
