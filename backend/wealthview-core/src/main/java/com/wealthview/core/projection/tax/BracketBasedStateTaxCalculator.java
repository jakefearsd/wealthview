package com.wealthview.core.projection.tax;

import com.wealthview.persistence.entity.StateStandardDeductionEntity;
import com.wealthview.persistence.entity.StateTaxBracketEntity;
import com.wealthview.persistence.entity.StateTaxSurchargeEntity;
import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BracketBasedStateTaxCalculator implements StateTaxCalculator {

    private final String stateCode;
    private final boolean capitalGainsAsOrdinary;
    private final StateTaxBracketRepository bracketRepository;
    private final StateStandardDeductionRepository deductionRepository;
    private final StateTaxSurchargeRepository surchargeRepository;

    private final Map<String, List<StateTaxBracketEntity>> bracketCache = new HashMap<>();
    private final Map<String, BigDecimal> deductionCache = new HashMap<>();
    private final Map<String, List<StateTaxSurchargeEntity>> surchargeCache = new HashMap<>();

    public BracketBasedStateTaxCalculator(String stateCode,
                                           boolean capitalGainsAsOrdinary,
                                           StateTaxBracketRepository bracketRepository,
                                           StateStandardDeductionRepository deductionRepository,
                                           StateTaxSurchargeRepository surchargeRepository) {
        this.stateCode = Objects.requireNonNull(stateCode);
        this.capitalGainsAsOrdinary = capitalGainsAsOrdinary;
        this.bracketRepository = bracketRepository;
        this.deductionRepository = deductionRepository;
        this.surchargeRepository = surchargeRepository;
    }

    @Override
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
            Integer maxYear = bracketRepository.findMaxTaxYearByStateCode(stateCode);
            if (maxYear != null) {
                brackets = loadBrackets(maxYear, status);
            }
        }
        if (brackets.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal bracketTax = computeBracketTax(taxableIncome, brackets);
        BigDecimal surcharge = computeSurcharge(taxableIncome, taxYear, status);

        return bracketTax.add(surcharge).setScale(SCALE, ROUNDING);
    }

    @Override
    public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
        return loadStandardDeduction(taxYear, status);
    }

    @Override
    public String stateCode() {
        return stateCode;
    }

    @Override
    public boolean taxesCapitalGainsAsOrdinaryIncome() {
        return capitalGainsAsOrdinary;
    }

    private BigDecimal computeBracketTax(BigDecimal taxableIncome, List<StateTaxBracketEntity> brackets) {
        BigDecimal totalTax = BigDecimal.ZERO;
        BigDecimal remaining = taxableIncome;

        for (var bracket : brackets) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

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

        return totalTax;
    }

    private BigDecimal computeSurcharge(BigDecimal taxableIncome, int taxYear, FilingStatus status) {
        var surcharges = loadSurcharges(taxYear, status);
        BigDecimal totalSurcharge = BigDecimal.ZERO;

        for (var surcharge : surcharges) {
            BigDecimal excess = taxableIncome.subtract(surcharge.getIncomeThreshold()).max(BigDecimal.ZERO);
            if (excess.compareTo(BigDecimal.ZERO) > 0) {
                totalSurcharge = totalSurcharge.add(excess.multiply(surcharge.getRate()));
            }
        }

        return totalSurcharge;
    }

    private BigDecimal loadStandardDeduction(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return deductionCache.computeIfAbsent(key, k -> {
            var entity = deductionRepository.findByStateCodeAndTaxYearAndFilingStatus(
                    stateCode, taxYear, status.value());
            if (entity.isPresent()) {
                return entity.get().getAmount();
            }
            Integer maxYear = deductionRepository.findMaxTaxYearByStateCode(stateCode);
            if (maxYear != null) {
                return deductionRepository.findByStateCodeAndTaxYearAndFilingStatus(
                                stateCode, maxYear, status.value())
                        .map(StateStandardDeductionEntity::getAmount)
                        .orElse(BigDecimal.ZERO);
            }
            return BigDecimal.ZERO;
        });
    }

    private List<StateTaxBracketEntity> loadBrackets(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return bracketCache.computeIfAbsent(key,
                k -> bracketRepository.findByStateCodeAndTaxYearAndFilingStatusOrderByBracketFloorAsc(
                        stateCode, taxYear, status.value()));
    }

    private List<StateTaxSurchargeEntity> loadSurcharges(int taxYear, FilingStatus status) {
        String key = taxYear + ":" + status.value();
        return surchargeCache.computeIfAbsent(key,
                k -> surchargeRepository.findByStateCodeAndTaxYearAndFilingStatus(
                        stateCode, taxYear, status.value()));
    }
}
