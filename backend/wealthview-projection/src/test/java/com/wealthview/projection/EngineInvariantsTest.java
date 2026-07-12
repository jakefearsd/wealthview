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
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
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
 * <h2>Invariant 2 — tax reconciliation (T18a-5b)</h2>
 * Whenever {@code federalTax} is non-null (retired, a tax strategy is wired, and {@code
 * taxLiability > 0} — see {@code RetirementTaxAnnotator#annotate}), {@code federalTax + stateTax
 * == taxLiability} to the cent. Self-employment tax and the early-withdrawal penalty are already
 * folded into {@code federalTax} by the annotator (see its javadoc); this class does not need to
 * add them separately.
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

        assertBalanceIdentity(sc, baseResult.yearlyData());
        assertTaxReconciliation(sc, baseResult.yearlyData());
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

    private void assertBalanceIdentity(ScenarioCase sc, List<ProjectionYearDto> years) {
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
                    .as("[%s] balance identity year %d (age %d)", sc.label(), y.year(), y.age())
                    .isLessThanOrEqualTo(tolerance);
        }
    }

    // === Invariant 2: tax reconciliation (T18a-5b) ===

    private void assertTaxReconciliation(ScenarioCase sc, List<ProjectionYearDto> years) {
        for (var y : years) {
            // "Defined" per the T18a-5b identity means RetirementTaxAnnotator's fold actually ran
            // (retired && taxStrategy != null && taxLiability > 0 -- see its javadoc), NOT merely
            // "federalTax happens to be non-null". A DISCOVERED, pre-existing DTO quirk (unrelated
            // to T18a-5b, out of scope to fix in this test-only task): when taxLiability nets to
            // zero via RetirementWithdrawalProcessor's alreadyChargedBaseTax offset, MultiPool's
            // buildYearDto can still surface a stale, non-netted federalTax from the pool's last
            // computeOrdinaryTax call (MultiPool.lastTaxBreakdown) because RetirementTaxAnnotator's
            // gate skips the fold -- that pre-annotator value was never claimed to reconcile with
            // taxLiability, so gating on taxLiability (the identity's own precondition) rather than
            // federalTax avoids a false failure on that unrelated gap.
            if (y.taxLiability() == null || y.taxLiability().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal total = nz(y.federalTax()).add(nz(y.stateTax()));
            assertThat(total)
                    .as("[%s] federalTax + stateTax == taxLiability year %d", sc.label(), y.year())
                    .isCloseTo(y.taxLiability(), offset(bd("0.01")));
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
                .filter(a -> "traditional".equals(a.accountType()))
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
