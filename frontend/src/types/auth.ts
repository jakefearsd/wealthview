export interface LoginRequest {
    email: string;
    password: string;
}

export interface RegisterRequest {
    email: string;
    password: string;
    invite_code: string;
}

/**
 * Identity fields returned in the body of login/register/refresh responses.
 * Tokens are NOT in the body — they are issued as HttpOnly cookies that
 * JavaScript cannot read.
 */
export interface AuthResponse {
    user_id: string;
    tenant_id: string;
    email: string;
    role: string;
}

/**
 * Outcome of a password login: either the session cookies were issued, or the
 * account has MFA enabled and the caller must finish with a code against
 * {@code mfa_token}.
 */
export type LoginResult =
    | { type: 'authenticated'; auth: AuthResponse }
    | { type: 'mfa_required'; mfa_token: string };

export interface CurrentUserResponse {
    user_id: string;
    tenant_id: string;
    email: string;
    role: string;
}
