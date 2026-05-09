/**
 * API type definitions shared between the web frontend and the React Native
 * mobile app.
 *
 * Field names mirror the backend's snake_case JSON output (Jackson is
 * configured globally with PropertyNamingStrategies.SNAKE_CASE) so these types
 * deserialize directly with no key remapping.
 */

export interface LoginRequest {
    email: string;
    password: string;
    /** Optional human-friendly device label surfaced in the per-device session list. */
    device_label?: string;
}

export interface RegisterRequest {
    email: string;
    password: string;
    invite_code: string;
    device_label?: string;
}

export interface RefreshRequest {
    refresh_token: string;
}

/** Raw shape returned by /api/v1/auth/token/{login,register,refresh} on success. */
export interface MobileAuthResponse {
    access_token: string;
    refresh_token: string;
    user_id: string;
    tenant_id: string;
    email: string;
    role: string;
}

/** Returned when the user has TOTP MFA enabled and must complete a challenge. */
export interface MfaRequiredResponse {
    mfa_required: true;
    mfa_token: string;
}

/**
 * Discriminated union returned by createAuthApi().login(). The backend can
 * answer with either a fully-issued token pair or an MFA challenge handle —
 * callers MUST check `type` before using the value.
 */
export type LoginOutcome =
    | { type: 'tokens'; tokens: MobileAuthResponse }
    | { type: 'mfa_required'; mfa_token: string };

export interface MeResponse {
    user_id: string;
    tenant_id: string;
    email: string;
    role: string;
}

/** Standard error envelope returned by the API for every 4xx / 5xx. */
export interface ErrorResponse {
    error: string;
    message: string;
    status: number;
}

/** Standard pagination envelope (matches backend PageResponse<T>). */
export interface PageResponse<T> {
    data: T[];
    page: number;
    size: number;
    total: number;
}

/** Bearer transport delivers tokens in the Authorization header (mobile). */
export type AuthTransport = 'bearer' | 'cookie';
