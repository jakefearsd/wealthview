package com.wealthview.core.auth.dto;

/**
 * Response body for the "MFA challenge required" branch of login, returned
 * identically by both {@code AuthController} (cookie transport) and
 * {@code AuthMobileController} (Bearer transport). The caller must POST
 * {@code mfaToken} plus a TOTP or recovery code to
 * {@code .../mfa/challenge} to finish authenticating.
 *
 * <p>Jackson is configured globally with
 * {@code PropertyNamingStrategies.SNAKE_CASE}, so these fields serialize as
 * {@code mfa_required} and {@code mfa_token} — the wire shape both
 * controllers returned previously via a raw {@code Map.of(...)}.
 */
public record MfaRequiredResponse(boolean mfaRequired, String mfaToken) {

    public static MfaRequiredResponse from(LoginOutcome.MfaRequired outcome) {
        return new MfaRequiredResponse(true, outcome.mfaToken());
    }

    /**
     * Redact the short-lived MFA challenge token, mirroring
     * {@link LoginOutcome.MfaRequired#toString()} — this record carries the
     * same sensitive value into the HTTP response body.
     */
    @Override
    public String toString() {
        return "MfaRequiredResponse[mfaRequired=" + mfaRequired + ", mfaToken=***]";
    }
}
