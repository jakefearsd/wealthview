package com.wealthview.core.property.dto;

import java.math.BigDecimal;

public record ZillowSearchResult(
        String zpid,
        String address,
        BigDecimal zestimate
) {
}
