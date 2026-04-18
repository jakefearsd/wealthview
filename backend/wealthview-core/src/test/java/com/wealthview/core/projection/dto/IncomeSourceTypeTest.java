package com.wealthview.core.projection.dto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncomeSourceTypeTest {

    @ParameterizedTest
    @CsvSource({
            "rental_property, RENTAL_PROPERTY",
            "social_security, SOCIAL_SECURITY",
            "pension,         PENSION",
            "part_time_work,  PART_TIME_WORK",
            "annuity,         ANNUITY",
            "other,           OTHER",
    })
    void fromString_withCanonicalValue_returnsMatchingEnum(String canonical, IncomeSourceType expected) {
        assertThat(IncomeSourceType.fromString(canonical)).isEqualTo(expected);
    }

    @Test
    void getValue_returnsCanonicalDatabaseString() {
        assertThat(IncomeSourceType.RENTAL_PROPERTY.getValue()).isEqualTo("rental_property");
        assertThat(IncomeSourceType.SOCIAL_SECURITY.getValue()).isEqualTo("social_security");
    }

    @Test
    void fromString_withUnknownValue_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> IncomeSourceType.fromString("dividend"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'dividend'");
    }

    @Test
    void fromString_withEmptyValue_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> IncomeSourceType.fromString(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
