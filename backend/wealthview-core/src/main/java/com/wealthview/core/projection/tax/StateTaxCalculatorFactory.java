package com.wealthview.core.projection.tax;

import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class StateTaxCalculatorFactory {

    private static final Set<String> NO_INCOME_TAX_STATES = Set.of(
            "AK", "FL", "NV", "NH", "SD", "TN", "TX", "WA", "WY");

    private static final NullStateTaxCalculator NULL_CALCULATOR = new NullStateTaxCalculator();

    private final StateTaxBracketRepository bracketRepository;
    private final StateStandardDeductionRepository deductionRepository;
    private final StateTaxSurchargeRepository surchargeRepository;

    public StateTaxCalculatorFactory(StateTaxBracketRepository bracketRepository,
                                      StateStandardDeductionRepository deductionRepository,
                                      StateTaxSurchargeRepository surchargeRepository) {
        this.bracketRepository = bracketRepository;
        this.deductionRepository = deductionRepository;
        this.surchargeRepository = surchargeRepository;
    }

    public StateTaxCalculator forState(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return NULL_CALCULATOR;
        }

        String normalized = stateCode.toUpperCase(Locale.US);

        if (NO_INCOME_TAX_STATES.contains(normalized)) {
            return NULL_CALCULATOR;
        }

        return switch (normalized) {
            case "CA" -> new CaliforniaStateTaxCalculator(bracketRepository, deductionRepository, surchargeRepository);
            case "AZ", "OR" -> new BracketBasedStateTaxCalculator(
                    normalized, true, bracketRepository, deductionRepository, surchargeRepository);
            default -> NULL_CALCULATOR;
        };
    }
}
