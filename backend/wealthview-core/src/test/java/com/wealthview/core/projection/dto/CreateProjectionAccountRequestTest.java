package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateProjectionAccountRequestTest {

    private static CreateProjectionAccountRequest account(UUID linkedAccountId, String balance, String costBasis) {
        return new CreateProjectionAccountRequest(linkedAccountId,
                balance == null ? null : new BigDecimal(balance), BigDecimal.ZERO, null,
                costBasis == null ? null : new BigDecimal(costBasis), "taxable");
    }

    @Test
    void isHypotheticalAmountsNonNegative_linkedRowWithNegativeLiveValues_isValid() {
        assertThat(account(UUID.randomUUID(), "-2000", "-2000").isHypotheticalAmountsNonNegative()).isTrue();
    }

    @Test
    void isHypotheticalAmountsNonNegative_unlinkedNegativeBalance_isInvalid() {
        assertThat(account(null, "-1", "0").isHypotheticalAmountsNonNegative()).isFalse();
    }

    @Test
    void isHypotheticalAmountsNonNegative_unlinkedNegativeCostBasis_isInvalid() {
        assertThat(account(null, "1000", "-1").isHypotheticalAmountsNonNegative()).isFalse();
    }

    @Test
    void isHypotheticalAmountsNonNegative_unlinkedNullAmounts_isValid() {
        assertThat(account(null, null, null).isHypotheticalAmountsNonNegative()).isTrue();
    }

    @Test
    void isHypotheticalAmountsNonNegative_negativeContribution_isValid() {
        var request = new CreateProjectionAccountRequest(null, new BigDecimal("1000"), new BigDecimal("-5000"),
                null, "taxable");

        assertThat(request.isHypotheticalAmountsNonNegative()).isTrue();
    }
}
