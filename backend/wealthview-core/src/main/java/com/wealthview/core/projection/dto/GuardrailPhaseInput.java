package com.wealthview.core.projection.dto;

public record GuardrailPhaseInput(
        String name,
        int startAge,
        Integer endAge,
        int priorityWeight
) {}
