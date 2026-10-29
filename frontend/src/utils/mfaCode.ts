/** A code typed at the MFA step, classified the way the server's challenge endpoint expects it. */
export type MfaCode =
    | { kind: 'totp'; value: string }
    | { kind: 'recovery'; value: string };

/** Shown when a typed code is in neither accepted shape. */
export const MFA_CODE_FORMAT_MESSAGE = 'Enter the 6-digit code from your authenticator app or an 8-character recovery code.';

const TOTP_CODE = /^\d{6}$/;
const RECOVERY_CODE = /^[A-Z0-9]{8}$/;

/**
 * Classifies a typed MFA code, ignoring spaces and dashes: six digits is an authenticator
 * (TOTP) code; eight letters/digits is a one-time recovery code, upper-cased to match how
 * the server issues them. Anything else returns null so the caller can ask again without
 * a round trip.
 */
export function parseMfaCode(input: string): MfaCode | null {
    const normalized = input.replace(/[\s-]/g, '').toUpperCase();
    if (TOTP_CODE.test(normalized)) {
        return { kind: 'totp', value: normalized };
    }
    if (RECOVERY_CODE.test(normalized)) {
        return { kind: 'recovery', value: normalized };
    }
    return null;
}
