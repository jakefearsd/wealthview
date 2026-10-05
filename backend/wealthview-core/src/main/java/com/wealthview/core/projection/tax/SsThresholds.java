package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

/** The two Social Security provisional-income thresholds for one year, already deflated to real terms. */
public record SsThresholds(BigDecimal base, BigDecimal upper) {
}
