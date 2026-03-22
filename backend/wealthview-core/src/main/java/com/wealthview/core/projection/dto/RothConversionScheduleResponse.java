package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.List;

public record RothConversionScheduleResponse(
        BigDecimal lifetimeTaxWithConversions,
        BigDecimal lifetimeTaxWithout,
        BigDecimal taxSavings,
        int exhaustionAge,
        boolean exhaustionTargetMet,
        BigDecimal conversionBracketRate,
        BigDecimal rmdTargetBracketRate,
        int traditionalExhaustionBuffer,
        BigDecimal mcExhaustionPct,
        List<ConversionYearDetail> years
) {}
