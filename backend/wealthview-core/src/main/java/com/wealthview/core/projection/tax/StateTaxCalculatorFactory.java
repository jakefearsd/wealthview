package com.wealthview.core.projection.tax;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.wealthview.persistence.repository.StateStandardDeductionRepository;
import com.wealthview.persistence.repository.StateTaxBracketRepository;
import com.wealthview.persistence.repository.StateTaxSurchargeRepository;

@Component
public class StateTaxCalculatorFactory {

    private static final Logger log = LoggerFactory.getLogger(StateTaxCalculatorFactory.class);

    private static final Set<String> NO_INCOME_TAX_STATES = Set.of(
            "AK", "FL", "NV", "NH", "SD", "TN", "TX", "WA", "WY");

    /** States with a dedicated {@link StateTaxCalculator} implementation (audit C3). */
    private static final Set<String> SUPPORTED_STATES = Set.of("CA", "AZ", "OR");

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

    /**
     * Resolves the {@link StateTaxCalculator} for a scenario's filing state, once per projection run
     * (called from {@code TaxStrategyFactory.buildTaxStrategy}, itself invoked once per {@code
     * DeterministicProjectionEngine.run} -- never inside the per-year loop). Logs a WARN, exactly
     * once per call (i.e. once per run), when the state has its own income tax but no modeled
     * calculator (audit C3): the tax is silently treated as $0 and callers should NOT rely on this
     * side effect alone -- see {@link #unsupportedStateWarning} for the same check surfaced on the
     * run response.
     */
    public StateTaxCalculator forState(String stateCode) {
        unsupportedStateWarning(stateCode).ifPresent(log::warn);

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

    /**
     * A warning message when {@code stateCode} has its own state income tax that WealthView does not
     * yet model (i.e. it resolves to {@link NullStateTaxCalculator} for a reason OTHER than genuinely
     * having no state income tax) -- empty for a blank/null state, a {@link #NO_INCOME_TAX_STATES}
     * member, or a {@link #SUPPORTED_STATES} member. Pure (no logging side effect) so it can be
     * reused both for {@link #forState}'s once-per-run log line and for surfacing the same message on
     * the API run response (audit C3).
     */
    public Optional<String> unsupportedStateWarning(String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            return Optional.empty();
        }

        String normalized = stateCode.toUpperCase(Locale.US);
        if (NO_INCOME_TAX_STATES.contains(normalized) || SUPPORTED_STATES.contains(normalized)) {
            return Optional.empty();
        }

        return Optional.of("State tax for " + normalized + " is not modeled (treated as $0)");
    }
}
