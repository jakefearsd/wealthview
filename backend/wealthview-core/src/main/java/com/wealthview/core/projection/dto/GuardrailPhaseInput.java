package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record GuardrailPhaseInput(
        String name,
        int startAge,
        Integer endAge,
        int priorityWeight,
        BigDecimal targetSpending
) {

    public GuardrailPhaseInput(String name, int startAge, Integer endAge, int priorityWeight) {
        this(name, startAge, endAge, priorityWeight, null);
    }
}
