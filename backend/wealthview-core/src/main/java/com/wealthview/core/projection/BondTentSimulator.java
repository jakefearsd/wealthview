package com.wealthview.core.projection;

import com.wealthview.core.projection.dto.BondTentSimulation;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.ProjectionInput;

/**
 * Simulates a scenario's current spending plan, held fixed, over Monte Carlo paths with no bond tent and with each
 * of the tent's mixes. Implemented in the projection module.
 */
@FunctionalInterface
public interface BondTentSimulator {

    /**
     * Simulates the scenario's spending plan with no tent and with each mix.
     *
     * @throws IllegalArgumentException if the scenario has no spending plan or no bond tent
     */
    BondTentSimulation simulate(GuardrailOptimizationInput input, ProjectionInput projectionInput);
}
