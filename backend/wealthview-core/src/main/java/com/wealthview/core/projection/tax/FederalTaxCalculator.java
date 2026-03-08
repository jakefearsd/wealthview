package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FederalTaxCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

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

        var brackets = loadBrackets(taxYear, status);
        if (brackets.isEmpty()) {
            Integer maxYear = taxBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
            }
        }
        if (brackets.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal remaining = taxableIncome;

        for (var bracket : brackets) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal bracketWidth;
            if (bracket.getBracketCeiling() != null) {
                bracketWidth = bracket.getBracketCeiling().subtract(bracket.getBracketFloor());
            } else {
                bracketWidth = remaining;
            }

            BigDecimal taxableInBracket = remaining.min(bracketWidth);
            totalTax = totalTax.add(taxableInBracket.multiply(bracket.getRate()));
            remaining = remaining.subtract(taxableInBracket);
        }

        return totalTax.setScale(SCALE, ROUNDING);
    }

    public BigDecimal computeMaxIncomeForBracket(BigDecimal targetRate, int taxYear, FilingStatus status) {
        var brackets = loadBrackets(taxYear, status);
        if (brackets.isEmpty()) {
            Integer maxYear = taxBracketRepository.findMaxTaxYear();
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
            }
        }

        BigDecimal deduction = loadStandardDeduction(taxYear, status);

        for (var bracket : brackets) {
            if (bracket.getRate().compareTo(targetRate) == 0) {
                if (bracket.getBracketCeiling() != null) {
                    return bracket.getBracketCeiling().add(deduction);
                }
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    public void clearCache() {
        bracketCache.clear();
        deductionCache.clear();
    }

    private BigDecimal loadStandardDeduction(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return deductionCache.computeIfAbsent(key, k -> {
            var entity = standardDeductionRepository.findByTaxYearAndFilingStatus(taxYear, status.value());
            if (entity.isPresent()) {
                return entity.get().getAmount();
            }
            Integer maxYear = standardDeductionRepository.findMaxTaxYear();
            if (maxYear != null) {
                return standardDeductionRepository.findByTaxYearAndFilingStatus(maxYear, status.value())
                        .map(e -> e.getAmount())
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
}
