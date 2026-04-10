export interface LoginRequest {
    email: string;
    password: string;
}

export interface RegisterRequest {
    email: string;
    password: string;
    invite_code: string;
}

export interface AuthResponse {
    access_token: string;
    refresh_token: string;
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
