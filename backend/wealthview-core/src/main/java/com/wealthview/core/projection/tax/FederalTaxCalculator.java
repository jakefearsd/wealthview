package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

// GodClass: this class already carried the full ordinary-tax surface (bracket loading/fallback,
// caching, the age-65 deduction seam, and the MC precompute's raw-bracket export) before household
// task 7; the per-person (count-aware) deduction overloads extend that SAME existing seam with a
// small, cohesive addition (no new external dependencies -- only the existing repositories/caches).
// Splitting the deduction logic into a separate class would separate two overloads of the same
// public method family, hurting readability for a WMC-driven metric rather than a real complexity
// problem. Mirrors the same documented call elsewhere (e.g. ScenarioCrudService, GuardrailProfileService).
@SuppressWarnings("PMD.GodClass")
@Component
public class FederalTaxCalculator {

    /**
     * Sentinel passed to the age-aware overloads by the age-less ones (via the 3-arg
     * {@link #loadStandardDeduction(int, FilingStatus)} / {@link #computeTax(BigDecimal, int,
     * FilingStatus)}): guaranteed below {@link #AGE_65}, so it reproduces the exact pre-age65-
     * feature behavior (base deduction only, no addition) for every caller that doesn't track age.
     */
    private static final int AGE_UNKNOWN = -1;

    /** IRS age threshold (Pub. 501) for the additional standard deduction. */
    private static final int AGE_65 = 65;

    private final TaxBracketRepository taxBracketRepository;
    private final StandardDeductionRepository standardDeductionRepository;
    // ConcurrentHashMap: this singleton is shared by concurrent projection requests, which
    // populate these caches via computeIfAbsent from multiple threads.
    private final Map<String, List<TaxBracketEntity>> bracketCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> deductionCache = new ConcurrentHashMap<>();

    public FederalTaxCalculator(TaxBracketRepository taxBracketRepository,
                                 StandardDeductionRepository standardDeductionRepository) {
        this.taxBracketRepository = taxBracketRepository;
        this.standardDeductionRepository = standardDeductionRepository;
    }

    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        return computeTax(grossIncome, taxYear, status, AGE_UNKNOWN);
    }

    /**
     * Like {@link #computeTax(BigDecimal, int, FilingStatus)} but applies the IRS age-65+
     * additional standard deduction (Pub. 501) when {@code age >= 65} for the given tax year, via
     * {@link #loadStandardDeduction(int, FilingStatus, int)}.
     */
    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status, int age) {
        return computeTax(grossIncome, taxYear, status, age, null);
    }

    /**
     * Household task 7 (spec §4 step 6): like {@link #computeTax(BigDecimal, int, FilingStatus, int)}
     * but applies the age-65+ additional standard deduction PER QUALIFYING PERSON -- {@code age}
     * always, plus a second time when {@code secondQualifyingAge} is non-null and itself 65+ (a
     * household's spouse while both are alive, filing jointly; see
     * {@link com.wealthview.core.projection.household.HouseholdContext#secondFilerAgeIn}). {@code
     * secondQualifyingAge} {@code null} reproduces {@link #computeTax(BigDecimal, int, FilingStatus,
     * int)} byte-for-byte -- every pre-household call site passes {@code null} implicitly.
     */
    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status, int age,
                                  @Nullable Integer secondQualifyingAge) {
        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal deduction = loadStandardDeduction(taxYear, status, age, secondQualifyingAge);
        BigDecimal taxableIncome = grossIncome.subtract(deduction).max(BigDecimal.ZERO);
        if (taxableIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        var brackets = loadBracketsWithFallback(taxYear, status);
        return iterateBrackets(taxableIncome, brackets);
    }

    public BigDecimal computeTaxWithDeduction(BigDecimal grossIncome, BigDecimal deduction,
                                                int taxYear, FilingStatus status) {
        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal taxableIncome = grossIncome.subtract(deduction).max(BigDecimal.ZERO);
        if (taxableIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        var brackets = loadBracketsWithFallback(taxYear, status);
        return iterateBrackets(taxableIncome, brackets);
    }

    public BigDecimal computeMaxIncomeForBracket(BigDecimal targetRate, int taxYear, FilingStatus status) {
        return computeMaxIncomeForBracket(targetRate, taxYear, status, null);
    }

    /**
     * Returns the gross income ceiling for the given bracket rate, with optional
     * inflation indexing. When brackets for the requested tax year are not seeded,
     * falls back to the latest available year and inflates thresholds by
     * {@code bracketInflationRate} per year gap — matching the IRS practice of
     * annually indexing brackets to CPI.
     */
    // AvoidBranchingStatementAsLastInLoop: this is an intentional find-first-match-and-return
    // loop — the return exits as soon as the target bracket is located. Rewriting it to break
    // into a captured variable would be strictly less clear.
    @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
    public BigDecimal computeMaxIncomeForBracket(BigDecimal targetRate, int taxYear,
                                                   FilingStatus status,
                                                   BigDecimal bracketInflationRate) {
        var brackets = loadBrackets(taxYear, status);
        BigDecimal inflationFactor = BigDecimal.ONE;
        if (brackets.isEmpty()) {
            Integer maxYear = taxBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
                inflationFactor = bracketInflationFactor(bracketInflationRate, taxYear, maxYear);
            }
        }

        BigDecimal deduction = inflate(loadStandardDeduction(taxYear, status), inflationFactor);

        for (var bracket : brackets) {
            if (bracket.getRate().compareTo(targetRate) != 0) {
                continue;
            }
            if (bracket.getBracketCeiling() == null) {
                return BigDecimal.ZERO;
            }
            return inflate(bracket.getBracketCeiling(), inflationFactor).add(deduction);
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal bracketInflationFactor(BigDecimal rate, int taxYear, int maxYear) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || taxYear <= maxYear) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(rate).pow(taxYear - maxYear);
    }

    private static BigDecimal inflate(BigDecimal value, BigDecimal factor) {
        if (factor.compareTo(BigDecimal.ONE) <= 0) {
            return value;
        }
        return value.multiply(factor).setScale(SCALE, ROUNDING);
    }

    /**
     * Returns the taxable income ceiling for the given federal bracket rate,
     * WITHOUT adding any deduction. Returns ZERO if the rate is not found
     * or is the top bracket (which has no ceiling).
     */
    BigDecimal findBracketCeiling(BigDecimal targetRate, int taxYear, FilingStatus status) {
        var brackets = loadBracketsWithFallback(taxYear, status);
        for (var bracket : brackets) {
            if (bracket.getRate().compareTo(targetRate) == 0) {
                return bracket.getBracketCeiling() != null ? bracket.getBracketCeiling() : BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    public void clearCache() {
        bracketCache.clear();
        deductionCache.clear();
    }

    /**
     * Returns the ordinary federal tax brackets for {@code (taxYear, status)}, floor-ascending,
     * using the SAME year-fallback {@link #computeTax} applies internally
     * ({@link #loadBracketsWithFallback}). A {@code null} ceiling on the last entry is the top,
     * uncapped bracket.
     *
     * <p>Exposed for the Monte Carlo engine's per-year exact-tax precompute (audit C5), which
     * builds an allocation-free primitive-array lookup table from this raw data instead of
     * repeating BigDecimal {@link #computeTax} calls inside the hot trial loop.
     */
    public List<BracketPoint> loadOrdinaryBrackets(int taxYear, FilingStatus status) {
        return loadBracketsWithFallback(taxYear, status).stream()
                .map(b -> new BracketPoint(b.getBracketFloor(), b.getBracketCeiling(), b.getRate()))
                .toList();
    }

    /**
     * The federal standard deduction for (taxYear, status), falling back to the latest seeded year
     * when the exact year isn't present. Public so collaborators outside this package (e.g. the
     * projection module's LTCG stacking-floor netting) can reuse the SAME deduction the ordinary tax
     * path nets internally, rather than re-deriving or omitting it.
     *
     * <p>Age-unaware: equivalent to {@link #loadStandardDeduction(int, FilingStatus, int)} with
     * {@link #AGE_UNKNOWN}, i.e. never applies the age-65+ addition. Existing callers that don't
     * track age keep exactly their pre-age65-feature behavior.
     */
    public BigDecimal loadStandardDeduction(int taxYear, FilingStatus status) {
        return loadStandardDeduction(taxYear, status, AGE_UNKNOWN);
    }

    /**
     * Age-aware standard deduction (Tier-3 audit item D): adds the IRS age-65+ additional standard
     * deduction (Pub. 501, {@code StandardDeductionEntity#getAdditionalAge65()}) on top of the base
     * amount when {@code age >= 65} for {@code taxYear}, with the same latest-seeded-year fallback
     * as the base amount. Household task 7 supersedes this overload's original single-adder
     * simplification with {@link #loadStandardDeduction(int, FilingStatus, int, Integer)}, which
     * applies the true per-QUALIFYING-PERSON amount for an MFJ household where both spouses are
     * 65+; this 3-arg overload is unchanged (equivalent to passing a {@code null} second age).
     */
    public BigDecimal loadStandardDeduction(int taxYear, FilingStatus status, int age) {
        return loadStandardDeduction(taxYear, status, age, null);
    }

    /**
     * Household task 7 (spec §4 step 6): per-QUALIFYING-PERSON age-65 additional standard deduction.
     * Adds the adder ONCE for {@code age} and a SECOND time when {@code secondQualifyingAge} is
     * non-null and itself 65+ (a household's spouse, while both are alive and filing jointly -- see
     * {@link com.wealthview.core.projection.household.HouseholdContext#secondFilerAgeIn}). Per
     * {@code StandardDeductionEntity}'s Javadoc the seeded amount IS per qualifying person, so this
     * reproduces the true MFJ-both-65+ adder that {@link #loadStandardDeduction(int, FilingStatus,
     * int)} previously understated at exactly one adder. {@code secondQualifyingAge} {@code null}
     * reproduces the single-age overload byte-for-byte -- every pre-household call site passes
     * {@code null} implicitly via that overload.
     */
    public BigDecimal loadStandardDeduction(int taxYear, FilingStatus status, int age,
                                             @Nullable Integer secondQualifyingAge) {
        int qualifyingPersons = (age >= AGE_65 ? 1 : 0)
                + (secondQualifyingAge != null && secondQualifyingAge >= AGE_65 ? 1 : 0);
        String key = taxYear + ":" + status.value() + ":" + qualifyingPersons;
        return deductionCache.computeIfAbsent(key, k -> resolveStandardDeduction(taxYear, status, qualifyingPersons));
    }

    private BigDecimal resolveStandardDeduction(int taxYear, FilingStatus status, int qualifyingPersons) {
        var entity = standardDeductionRepository.findByTaxYearAndFilingStatus(taxYear, status.value());
        if (entity.isEmpty()) {
            Integer maxYear = standardDeductionRepository.findMaxTaxYear();
            entity = maxYear != null
                    ? standardDeductionRepository.findByTaxYearAndFilingStatus(maxYear, status.value())
                    : Optional.empty();
        }
        return entity.map(e -> applyAge65Addition(e, qualifyingPersons)).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal applyAge65Addition(StandardDeductionEntity entity, int qualifyingPersons) {
        return qualifyingPersons > 0
                ? entity.getAmount().add(entity.getAdditionalAge65().multiply(BigDecimal.valueOf(qualifyingPersons)))
                : entity.getAmount();
    }

    private List<TaxBracketEntity> loadBrackets(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return bracketCache.computeIfAbsent(key,
                k -> taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        taxYear, status.value()));
    }

    private List<TaxBracketEntity> loadBracketsWithFallback(int taxYear, FilingStatus status) {
        var brackets = loadBrackets(taxYear, status);
        if (brackets.isEmpty()) {
            Integer maxYear = taxBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
            }
        }
        return brackets;
    }

    private BigDecimal iterateBrackets(BigDecimal taxableIncome, List<TaxBracketEntity> brackets) {
        if (brackets.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal remaining = taxableIncome;
        for (var bracket : brackets) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal bracketWidth = bracket.getBracketCeiling() != null
                    ? bracket.getBracketCeiling().subtract(bracket.getBracketFloor())
                    : remaining;
            BigDecimal taxableInBracket = remaining.min(bracketWidth);
            totalTax = totalTax.add(taxableInBracket.multiply(bracket.getRate()));
            remaining = remaining.subtract(taxableInBracket);
        }
        return totalTax.setScale(SCALE, ROUNDING);
    }
}
