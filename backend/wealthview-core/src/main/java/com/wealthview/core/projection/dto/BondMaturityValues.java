package com.wealthview.core.projection.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One decimal value per {@link BondMaturity}: a mix's shares, or the nominal yields. The wire keys
 * are exactly {@code ultrashort}, {@code short}, {@code intermediate} and {@code long}; the Java
 * component names for the two reserved words are {@code shortTerm} and {@code longTerm}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BondMaturityValues(
        BigDecimal ultrashort,
        @JsonProperty("short") BigDecimal shortTerm,
        BigDecimal intermediate,
        @JsonProperty("long") BigDecimal longTerm) {

    public BigDecimal get(BondMaturity maturity) {
        return switch (maturity) {
            case ULTRASHORT -> ultrashort;
            case SHORT -> shortTerm;
            case INTERMEDIATE -> intermediate;
            case LONG -> longTerm;
        };
    }

    /** The sum of the four values, treating a missing (null) value as zero. */
    public BigDecimal sum() {
        var total = BigDecimal.ZERO;
        for (var maturity : BondMaturity.values()) {
            var value = get(maturity);
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }
}
