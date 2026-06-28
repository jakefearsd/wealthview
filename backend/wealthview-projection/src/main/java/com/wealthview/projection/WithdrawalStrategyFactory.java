package com.wealthview.projection;

import java.math.BigDecimal;

import com.wealthview.core.projection.strategy.DynamicPercentageWithdrawal;
import com.wealthview.core.projection.strategy.FixedPercentageWithdrawal;
import com.wealthview.core.projection.strategy.VanguardDynamicSpendingWithdrawal;
import com.wealthview.core.projection.strategy.WithdrawalStrategy;

/**
 * Creates the {@link WithdrawalStrategy} for a projection from the parsed scenario parameters,
 * defaulting to a fixed-percentage withdrawal. Extracted from {@link DeterministicProjectionEngine}.
 */
final class WithdrawalStrategyFactory {

    private WithdrawalStrategyFactory() {
    }

    static WithdrawalStrategy create(ScenarioParamsParser.ScenarioParams params, BigDecimal withdrawalRate) {
        if (params.withdrawalStrategy() == null || params.withdrawalStrategy().isBlank()) {
            return new FixedPercentageWithdrawal(withdrawalRate);
        }
        return switch (params.withdrawalStrategy()) {
            case "dynamic_percentage" -> new DynamicPercentageWithdrawal(withdrawalRate);
            case "vanguard_dynamic_spending" -> {
                BigDecimal ceiling = params.dynamicCeiling() != null
                        ? params.dynamicCeiling() : new BigDecimal("0.05");
                BigDecimal floor = params.dynamicFloor() != null
                        ? params.dynamicFloor() : new BigDecimal("-0.025");
                yield new VanguardDynamicSpendingWithdrawal(withdrawalRate, ceiling, floor);
            }
            default -> new FixedPercentageWithdrawal(withdrawalRate);
        };
    }
}
