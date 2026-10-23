package com.wealthview.core.projection.dto;

import java.util.List;

/** The bond tent side channel of a projection run: how it was sized and how it evolved year by year. */
public record BondTentResult(BondTentSizing sizing, List<BondTentYear> years) {
}
