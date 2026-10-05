package com.wealthview.core.projection.tax;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealTermsDeflatorTest {

    @Test
    void factor_zeroYears_isOne() {
        assertThat(RealTermsDeflator.factor(0, new BigDecimal("0.025"))).isEqualByComparingTo("1");
    }

    @Test
    void factor_negativeYears_isOne() {
        assertThat(RealTermsDeflator.factor(-3, new BigDecimal("0.025"))).isEqualByComparingTo("1");
    }

    @Test
    void factor_nullOrZeroRate_isOne() {
        assertThat(RealTermsDeflator.factor(10, null)).isEqualByComparingTo("1");
        assertThat(RealTermsDeflator.factor(10, BigDecimal.ZERO)).isEqualByComparingTo("1");
    }

    @Test
    void factor_tenYearsAtTwoPointFivePercent_matchesClosedForm() {
        // 1.025^10 = 1.2800845441963578... ; 1 / that = 0.78119840172..., HALF_UP at scale 10
        assertThat(RealTermsDeflator.factor(10, new BigDecimal("0.025"))).isEqualByComparingTo("0.7811984017");
    }
}
