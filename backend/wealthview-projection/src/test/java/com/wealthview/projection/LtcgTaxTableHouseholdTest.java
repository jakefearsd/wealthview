package com.wealthview.projection;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025WithAge65Adder;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025WithAge65Adder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Household task 7 (spec §4 step 6) on the capital-gains side: the age-65 standard-deduction adder
 * is PER QUALIFYING PERSON, so a married-filing-jointly household with both members 65+ nets a
 * larger deduction than a single 65+ filer — which lowers the stacking floor the LTCG bracket walk
 * starts from, and can drop a gain from the 15% bracket into the 0% bracket entirely.
 *
 * <p>{@link LtcgTaxTableTest} covers {@code taxAt} and the pre-household {@code build}/
 * {@code computeAll} overloads; this class covers the household-aware ones — the {@code secondAge}
 * {@code build} overload and {@code computeAll}'s per-year household age derivation, including its
 * collapse to a single filer after the first death.
 *
 * <p>All cases share one probe: a $127,000 gross ordinary floor and a $2,000 realized gain against
 * the 2025 MFJ brackets (0% up to $96,700). That floor is chosen so each distinct number of
 * age-65-qualifying people lands the gain in a different place, making the per-person multiplier
 * directly observable in the tax:
 * <ul>
 *   <li>0 qualifying → deduction 30,000 → netted floor 97,000 → whole gain at 15% → $300.00</li>
 *   <li>1 qualifying → deduction 31,600 → netted floor 95,400 → 1,300 at 0% + 700 at 15% → $105.00</li>
 *   <li>2 qualifying → deduction 33,200 → netted floor 93,800 → whole gain inside 0% → $0.00</li>
 * </ul>
 * MAGI stays at 129,000, far below the $250,000 MFJ NIIT threshold, so no case carries NIIT.
 */
class LtcgTaxTableHouseholdTest {

    private static final double FLOOR = 127_000;
    private static final double GAIN = 2_000;

    private static final double TAX_NO_ONE_65 = 300.0;
    private static final double TAX_ONE_65 = 105.0;
    private static final double TAX_BOTH_65 = 0.0;

    /** Single-filer probe (see {@link #computeAll_householdFilingSingle_ignoresTheSpousesAge()}):
     * 0% single bracket ends at 48,350, so this floor separates one adder from two. */
    private static final double SINGLE_FLOOR = 66_000;
    private static final double TAX_SINGLE_ONE_65 = 300.0;

    /** Both members comfortably outlive the horizon, so the household never transitions. */
    private static final int NO_TRANSITION_DEATH_AGE = 95;
    private static final int HORIZON_END_YEAR = 2060;

    private static CapitalGainsTaxCalculator mfjCapitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubMfj2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static FederalTaxCalculator mfjFederalCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubMfj2025WithAge65Adder(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private static CapitalGainsTaxCalculator singleCapitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static FederalTaxCalculator singleFederalCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025WithAge65Adder(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    private static double taxFor(int age, Integer secondAge) {
        var table = LtcgTaxTable.build(mfjCapitalGainsCalc(), mfjFederalCalc(), 2025,
                FilingStatus.MARRIED_FILING_JOINTLY, 0, 0.0, age, secondAge);
        return table.taxAt(FLOOR, GAIN);
    }

    // === build(..., age, secondAge): the per-person adder ===

    @Test
    void build_bothFilers65Plus_appliesAge65AdderTwiceAndDropsGainIntoZeroBracket() {
        assertThat(taxFor(70, 70))
                .as("two qualifying filers deduct 30,000 + 2 x 1,600 = 33,200, netting the floor to "
                        + "93,800 so the whole 2,000 gain fits inside the 0% bracket")
                .isCloseTo(TAX_BOTH_65, within(1e-6));
    }

    @Test
    void build_onlyPrimaryFiler65Plus_appliesAge65AdderOnce() {
        assertThat(taxFor(70, null))
                .as("one qualifying filer deducts 30,000 + 1,600 = 31,600")
                .isCloseTo(TAX_ONE_65, within(1e-6));
    }

    @Test
    void build_secondFilerPresentButUnder65_doesNotApplyASecondAdder() {
        assertThat(taxFor(70, 60))
                .as("a non-null secondAge below 65 must not qualify — same as no spouse at all")
                .isCloseTo(TAX_ONE_65, within(1e-6));
    }

    @Test
    void build_onlySecondFiler65Plus_stillAppliesAdderOnce() {
        assertThat(taxFor(60, 70))
                .as("the adder counts qualifying people, not which of them is the primary")
                .isCloseTo(TAX_ONE_65, within(1e-6));
    }

    @Test
    void build_neitherFiler65_appliesNoAdder() {
        assertThat(taxFor(60, 60)).isCloseTo(TAX_NO_ONE_65, within(1e-6));
    }

    // === computeAll(..., household): per-year household ages ===

    @Test
    void computeAll_withHousehold_derivesEachYearsDeductionFromThatYearsHouseholdAges() {
        // Primary born 1960, spouse born 1962: the spouse only reaches 65 in 2027, so the second
        // adder switches on partway through the horizon. A fixed birthYear cannot express this.
        var household = HouseholdContext.of(1960, NO_TRANSITION_DEATH_AGE, 1962, NO_TRANSITION_DEATH_AGE,
                HORIZON_END_YEAR);

        var tables = LtcgTaxTable.computeAll(mfjCapitalGainsCalc(), mfjFederalCalc(), 2025, 3,
                FilingStatus.MARRIED_FILING_JOINTLY, 0.0, null, household);

        assertThat(tables).hasSize(3);
        assertThat(tables[0].taxAt(FLOOR, GAIN)).as("2025: primary 65, spouse 63 -> one adder")
                .isCloseTo(TAX_ONE_65, within(1e-6));
        assertThat(tables[1].taxAt(FLOOR, GAIN)).as("2026: primary 66, spouse 64 -> still one adder")
                .isCloseTo(TAX_ONE_65, within(1e-6));
        assertThat(tables[2].taxAt(FLOOR, GAIN)).as("2027: spouse turns 65 -> second adder engages")
                .isCloseTo(TAX_BOTH_65, within(1e-6));
    }

    @Test
    void computeAll_householdAfterFirstDeath_dropsBackToASingleAge65Adder() {
        // Primary born 1960 dies in 2026 (alive iff year < 1960 + 66); spouse born 1958 survives.
        var household = HouseholdContext.of(1960, 66, 1958, NO_TRANSITION_DEATH_AGE, HORIZON_END_YEAR);

        var tables = LtcgTaxTable.computeAll(mfjCapitalGainsCalc(), mfjFederalCalc(), 2025, 3,
                FilingStatus.MARRIED_FILING_JOINTLY, 0.0, null, household);

        assertThat(tables[0].taxAt(FLOOR, GAIN))
                .as("2025: both alive and both 65+ (primary 65, spouse 67) -> two adders")
                .isCloseTo(TAX_BOTH_65, within(1e-6));
        assertThat(tables[1].taxAt(FLOOR, GAIN))
                .as("2026: primary has died, so only the surviving spouse's adder remains")
                .isCloseTo(TAX_ONE_65, within(1e-6));
        assertThat(tables[2].taxAt(FLOOR, GAIN))
                .as("2027: still a lone survivor -> one adder")
                .isCloseTo(TAX_ONE_65, within(1e-6));
    }

    @Test
    void computeAll_householdFilingSingle_ignoresTheSpousesAge() {
        // A household whose members are BOTH 65+, but filing SINGLE: only the filer's own adder may
        // apply. Probed against the single-2025 brackets (0% up to 48,350) with a 66,000 floor,
        // chosen so one adder and two adders give different answers:
        //   1 qualifying -> deduction 17,000 -> netted 49,000 -> whole gain at 15%      -> $300.00
        //   2 qualifying -> deduction 19,000 -> netted 47,000 -> 1,350 at 0% + 650 @ 15% -> $97.50
        // so wrongly threading the spouse's age through would be caught, not silently absorbed.
        var household = HouseholdContext.of(1955, NO_TRANSITION_DEATH_AGE, 1955, NO_TRANSITION_DEATH_AGE,
                HORIZON_END_YEAR);

        var tables = LtcgTaxTable.computeAll(singleCapitalGainsCalc(), singleFederalCalc(), 2025, 1,
                FilingStatus.SINGLE, 0.0, null, household);

        assertThat(tables).hasSize(1);
        assertThat(tables[0].taxAt(SINGLE_FLOOR, GAIN))
                .as("filing SINGLE must apply exactly one age-65 adder even for a two-person household")
                .isCloseTo(TAX_SINGLE_ONE_65, within(1e-6));
    }

    @Test
    void computeAll_withoutHousehold_derivesAgeFromBirthYear() {
        // Born 1962: turns 65 in 2027, so the adder engages only in the final year.
        var tables = LtcgTaxTable.computeAll(mfjCapitalGainsCalc(), mfjFederalCalc(), 2025, 3,
                FilingStatus.MARRIED_FILING_JOINTLY, 0.0, 1962, null);

        assertThat(tables[0].taxAt(FLOOR, GAIN)).as("2025: age 63 -> no adder")
                .isCloseTo(TAX_NO_ONE_65, within(1e-6));
        assertThat(tables[1].taxAt(FLOOR, GAIN)).as("2026: age 64 -> no adder")
                .isCloseTo(TAX_NO_ONE_65, within(1e-6));
        assertThat(tables[2].taxAt(FLOOR, GAIN)).as("2027: age 65 -> adder engages")
                .isCloseTo(TAX_ONE_65, within(1e-6));
    }

    @Test
    void computeAll_withoutHouseholdAndNoBirthYear_treatsAgeAsUnknownAndAppliesNoAdder() {
        var tables = LtcgTaxTable.computeAll(mfjCapitalGainsCalc(), mfjFederalCalc(), 2025, 2,
                FilingStatus.MARRIED_FILING_JOINTLY, 0.0, null, null);

        assertThat(tables).hasSize(2);
        assertThat(tables[0].taxAt(FLOOR, GAIN)).isCloseTo(TAX_NO_ONE_65, within(1e-6));
        assertThat(tables[1].taxAt(FLOOR, GAIN)).isCloseTo(TAX_NO_ONE_65, within(1e-6));
    }
}
