package com.wealthview.core.projection.tax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FilingStatusTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void resolve_unsetWithSpouse_marriedFilingJointly(String filingStatus) {
        assertThat(FilingStatus.resolve(filingStatus, 1962)).isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void resolve_unsetWithoutSpouse_single(String filingStatus) {
        assertThat(FilingStatus.resolve(filingStatus, null)).isEqualTo(FilingStatus.SINGLE);
    }

    @Test
    void resolve_explicitSingleWithSpouse_keepsUsersChoice() {
        assertThat(FilingStatus.resolve("single", 1962)).isEqualTo(FilingStatus.SINGLE);
    }

    @Test
    void resolve_explicitMarriedWithoutSpouse_keepsUsersChoice() {
        assertThat(FilingStatus.resolve("married_filing_jointly", null))
                .isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);
    }

    @Test
    void resolve_explicitValue_isCaseInsensitive() {
        assertThat(FilingStatus.resolve("MARRIED_FILING_JOINTLY", null))
                .isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);
    }
}
