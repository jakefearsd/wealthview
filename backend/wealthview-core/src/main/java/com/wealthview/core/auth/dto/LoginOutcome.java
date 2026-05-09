package com.wealthview.core.auth.dto;

/**
 * Result of a login attempt. Two shapes:
 *
 * <ul>
 *   <li>{@link Tokens} — credentials valid and MFA not required; tokens are
 *       attached for the controller to surface as cookies or response body.</li>
 *   <li>{@link MfaRequired} — credentials valid but the account has MFA
 *       enabled. The caller must POST the {@code mfaToken} plus a TOTP or
 *       recovery code to {@code /api/v1/auth/mfa/challenge} to finish login.</li>
 * </ul>
 */
public sealed interface LoginOutcome permits LoginOutcome.Tokens, LoginOutcome.MfaRequired {

    record Tokens(AuthResult result) implements LoginOutcome {
    }

    record MfaRequired(String mfaToken) implements LoginOutcome {
        @Override
        public String toString() {
            return "MfaRequired[mfaToken=***]";
        }
    }
}
