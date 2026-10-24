package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The scenario's spending plan simulated over shared Monte Carlo paths with no tent and with each mix. The no-tent
 * column comes first; {@code plannedYears} is the tent's configured coverage.
 */
public record BondTentSimulation(int trialCount, BigDecimal plannedYears, List<BondTentSimulationColumn> columns) {
}
