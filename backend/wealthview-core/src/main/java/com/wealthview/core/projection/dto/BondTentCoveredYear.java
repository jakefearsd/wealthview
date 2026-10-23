package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

/** One retired year the tent covers: the withdrawal the portfolio funds and the fraction of the year counted. */
public record BondTentCoveredYear(int year, BigDecimal withdrawal, BigDecimal fraction) {
}
