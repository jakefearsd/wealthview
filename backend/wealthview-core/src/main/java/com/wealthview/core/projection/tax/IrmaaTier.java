package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

/**
 * One IRMAA MAGI tier: "greater than {@code magiFloor}, up to and including {@code magiCeiling}"
 * ({@code null} ceiling = top tier), with the combined Part B + Part D surcharge annualized (x12)
 * for ONE Medicare enrollee.
 */
public record IrmaaTier(BigDecimal magiFloor, @Nullable BigDecimal magiCeiling,
                        BigDecimal annualSurchargePerPerson) {
}
