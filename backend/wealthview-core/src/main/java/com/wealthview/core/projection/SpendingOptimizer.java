package com.wealthview.core.projection;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;

@FunctionalInterface
public interface SpendingOptimizer {

    GuardrailProfileResponse optimize(GuardrailOptimizationInput input);
}
