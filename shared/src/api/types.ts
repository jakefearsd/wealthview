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

// ---------------------------------------------------------------------------
// Account
// ---------------------------------------------------------------------------

/**
 * The user-defined category an account belongs to. Mirrors the regex in
 * AccountRequest on the backend (`brokerage|ira|401k|roth|bank`) plus the
 * synthetic `property` type the dashboard summary emits for owned real
 * estate. New types should be added here AND on the backend's regex.
 */
export type AccountType = 'brokerage' | 'ira' | '401k' | 'roth' | 'bank' | 'property';

/**
 * One account row as returned by `GET /api/v1/accounts` and `GET
 * /api/v1/accounts/{id}`. Field names mirror the backend's
 * `AccountResponse` record verbatim (snake_case on the wire after Jackson's
 * naming strategy).
 *
 * `balance` is the current balance in the account's NATIVE currency — the
 * dashboard summary does the USD conversion separately.
 */
export interface AccountResponse {
    id: string;
    name: string;
    type: string;
    institution: string | null;
    currency: string;
    balance: number;
    created_at: string;
}

// ---------------------------------------------------------------------------
// Dashboard summary
// ---------------------------------------------------------------------------

/**
 * One row in the dashboard summary's flat account list. Note this is NOT
 * the same shape as {@link AccountResponse} — the summary intentionally
 * omits id/institution/currency to keep the payload small. The `balance`
 * here is in USD (the summary normalizes everything before returning).
 *
 * Properties also appear in this list with `type === 'property'` and the
 * property's address as the `name`.
 */
export interface DashboardAccountSummary {
    name: string;
    type: string;
    balance: number;
}

/** One slice of the allocation pie. Percentage is 0–100, NOT 0–1. */
export interface DashboardAllocationEntry {
    category: string;
    value: number;
    percentage: number;
}

/**
 * Response body for `GET /api/v1/dashboard/summary`. Monetary fields arrive
 * as JSON numbers (Jackson's default BigDecimal serialization — the backend
 * does not enable any as-string mode).
 */
export interface DashboardSummaryResponse {
    net_worth: number;
    total_investments: number;
    total_cash: number;
    total_property_equity: number;
    accounts: DashboardAccountSummary[];
    allocation: DashboardAllocationEntry[];
}

/** Optional pagination params accepted by `GET /api/v1/accounts`. */
export interface AccountsListParams {
    page?: number;
    size?: number;
}
