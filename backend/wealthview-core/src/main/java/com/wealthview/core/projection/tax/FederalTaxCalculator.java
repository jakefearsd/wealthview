package com.wealthview.core.projection.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

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
        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal deduction = loadStandardDeduction(taxYear, status, age);
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
     * as the base amount. Per {@code StandardDeductionEntity}'s Javadoc, the seeded amount is per
     * QUALIFYING PERSON (for MFJ, technically per spouse aged 65+) -- this engine only tracks the
     * PRIMARY filer's age, so callers apply at most ONE adder even for an MFJ couple where both
     * spouses are 65+. That's a documented, conservative simplification (understates the true
     * deduction, i.e. slightly overstates tax) rather than a correctness bug.
     */
    public BigDecimal loadStandardDeduction(int taxYear, FilingStatus status, int age) {
        String key = taxYear + ":" + status.value() + ":" + (age >= AGE_65);
        return deductionCache.computeIfAbsent(key, k -> resolveStandardDeduction(taxYear, status, age));
    }

    private BigDecimal resolveStandardDeduction(int taxYear, FilingStatus status, int age) {
        var entity = standardDeductionRepository.findByTaxYearAndFilingStatus(taxYear, status.value());
        if (entity.isEmpty()) {
            Integer maxYear = standardDeductionRepository.findMaxTaxYear();
            entity = maxYear != null
                    ? standardDeductionRepository.findByTaxYearAndFilingStatus(maxYear, status.value())
                    : Optional.empty();
        }
        return entity.map(e -> applyAge65Addition(e, age)).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal applyAge65Addition(StandardDeductionEntity entity, int age) {
        return age >= AGE_65 ? entity.getAmount().add(entity.getAdditionalAge65()) : entity.getAmount();
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
