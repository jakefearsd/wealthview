package com.wealthview.core.property.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PropertyValuationResult(
        BigDecimal value,
        LocalDate date
) {
}
