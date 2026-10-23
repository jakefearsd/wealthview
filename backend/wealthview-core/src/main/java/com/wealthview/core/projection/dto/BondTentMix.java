package com.wealthview.core.projection.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A named split of the tent across the four bond maturities; the shares sum to one. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BondTentMix(String name, BondMaturityValues shares) {
}
