package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

/** Remaining room (never negative) in the 0% and 15% LTCG bands after ordinary income stacks first. */
public record LtcgBandRoom(BigDecimal zeroRoom, BigDecimal fifteenRoom) {
}
