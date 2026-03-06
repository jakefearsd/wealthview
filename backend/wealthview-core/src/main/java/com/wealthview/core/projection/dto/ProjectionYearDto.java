package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

public record ProjectionYearDto(
        int year,
        int age,
        BigDecimal startBalance,
        BigDecimal contributions,
        BigDecimal growth,
        BigDecimal withdrawals,
        BigDecimal endBalance,
        boolean retired) {
}
