package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.StandardDeductionEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

@Component
public class FederalTaxCalculator {

    private final TaxBracketRepository taxBracketRepository;
    private final StandardDeductionRepository standardDeductionRepository;
    private final Map<String, List<TaxBracketEntity>> bracketCache = new HashMap<>();
    private final Map<String, BigDecimal> deductionCache = new HashMap<>();

    public FederalTaxCalculator(TaxBracketRepository taxBracketRepository,
                                 StandardDeductionRepository standardDeductionRepository) {
        this.taxBracketRepository = taxBracketRepository;
        this.standardDeductionRepository = standardDeductionRepository;
    }

    public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
        if (grossIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal deduction = loadStandardDeduction(taxYear, status);
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
    public BigDecimal computeMaxIncomeForBracket(BigDecimal targetRate, int taxYear,
                                                   FilingStatus status,
                                                   BigDecimal bracketInflationRate) {
        var brackets = loadBrackets(taxYear, status);
        BigDecimal inflationFactor = BigDecimal.ONE;
        if (brackets.isEmpty()) {
            Integer maxYear = taxBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
                if (bracketInflationRate != null
                        && bracketInflationRate.compareTo(BigDecimal.ZERO) > 0
                        && taxYear > maxYear) {
                    inflationFactor = BigDecimal.ONE.add(bracketInflationRate)
                            .pow(taxYear - maxYear);
                }
            }
        }

        BigDecimal deduction = loadStandardDeduction(taxYear, status);
        if (inflationFactor.compareTo(BigDecimal.ONE) > 0) {
            deduction = deduction.multiply(inflationFactor).setScale(SCALE, ROUNDING);
        }

        for (var bracket : brackets) {
            if (bracket.getRate().compareTo(targetRate) == 0) {
                if (bracket.getBracketCeiling() != null) {
                    BigDecimal ceiling = bracket.getBracketCeiling();
                    if (inflationFactor.compareTo(BigDecimal.ONE) > 0) {
                        ceiling = ceiling.multiply(inflationFactor).setScale(SCALE, ROUNDING);
                    }
                    return ceiling.add(deduction);
                }
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
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

    BigDecimal loadStandardDeduction(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return deductionCache.computeIfAbsent(key, k -> {
            var entity = standardDeductionRepository.findByTaxYearAndFilingStatus(taxYear, status.value());
            if (entity.isPresent()) {
                return entity.get().getAmount();
            }
            Integer maxYear = standardDeductionRepository.findMaxTaxYear();
            if (maxYear != null) {
                return standardDeductionRepository.findByTaxYearAndFilingStatus(maxYear, status.value())
                        .map(StandardDeductionEntity::getAmount)
                        .orElse(BigDecimal.ZERO);
            }
            return BigDecimal.ZERO;
        });
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
