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

export interface CurrentUserResponse {
    user_id: string;
    tenant_id: string;
    email: string;
    role: string;
}
