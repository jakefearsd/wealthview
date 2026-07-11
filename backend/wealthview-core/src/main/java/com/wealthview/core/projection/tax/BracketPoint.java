package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

/**
 * A single tax bracket, in the same floor/ceiling/rate shape {@link FederalTaxCalculator} and
 * {@link CapitalGainsTaxCalculator} already store internally. A {@code null} ceiling represents
 * the top, uncapped bracket.
 *
 * <p>Exposed publicly (audit C5) so hot-loop callers -- the Monte Carlo engine's per-year exact
 * tax precompute -- can replicate the exact bracket math with primitive arrays instead of
 * repeated BigDecimal {@code computeTax}/{@code computeLtcgTax} calls, which are too expensive to
 * call once per trial per year (10,000 trials x dozens of years).
 */
public record BracketPoint(BigDecimal floor, BigDecimal ceiling, BigDecimal rate) {}
