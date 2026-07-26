package com.wealthview.core.auth.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MfaRequiredResponseTest {

    @Test
    void from_mfaRequiredOutcome_setsRequiredTrueAndCopiesToken() {
        var outcome = new LoginOutcome.MfaRequired("real-mfa-challenge-token");

        var response = MfaRequiredResponse.from(outcome);

        assertThat(response.mfaRequired()).isTrue();
        assertThat(response.mfaToken()).isEqualTo("real-mfa-challenge-token");
    }

    @Test
    void toString_redactsMfaToken() {
        var response = new MfaRequiredResponse(true, "real-mfa-challenge-token");

        var rendered = response.toString();

        assertThat(rendered).doesNotContain("real-mfa-challenge-token");
        assertThat(rendered).contains("mfaToken=***");
        assertThat(rendered).contains("mfaRequired=true");
    }
}
