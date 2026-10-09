import axios from 'axios';
import client from './client';
import type { AuthResponse, CurrentUserResponse, LoginRequest, LoginResult, RegisterRequest } from '../types/auth';

const authClient = axios.create({
    baseURL: '/api/v1/auth',
    headers: { 'Content-Type': 'application/json' },
    withCredentials: true,
});

const TOTP_CODE = /^\d{6}$/;

interface MfaRequiredBody {
    mfa_required: true;
    mfa_token: string;
}

function isMfaRequired(body: AuthResponse | MfaRequiredBody): body is MfaRequiredBody {
    return 'mfa_required' in body && body.mfa_required === true;
}

export async function login(request: LoginRequest): Promise<LoginResult> {
    const { data } = await authClient.post<AuthResponse | MfaRequiredBody>('/login', request);
    if (isMfaRequired(data)) {
        return { type: 'mfa_required', mfa_token: data.mfa_token };
    }
    return { type: 'authenticated', auth: data };
}

/**
 * Finishes an MFA login. A six-digit code is sent as a TOTP code; anything
 * else is treated as a one-time recovery code (8 upper-case characters).
 */
export async function completeMfaChallenge(mfaToken: string, code: string): Promise<AuthResponse> {
    const normalized = code.replace(/[\s-]/g, '');
    const body = TOTP_CODE.test(normalized)
        ? { mfa_token: mfaToken, totp_code: normalized }
        : { mfa_token: mfaToken, recovery_code: normalized.toUpperCase() };
    const { data } = await authClient.post<AuthResponse>('/mfa/challenge', body);
    return data;
}

export async function register(request: RegisterRequest): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/register', request);
    return data;
}

/**
 * Refreshes auth cookies. The refresh token is read from the HttpOnly
 * {@code refresh_token} cookie by the backend; nothing is sent in the body.
 */
export async function refresh(): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/refresh', null);
    return data;
}

export async function logout(): Promise<void> {
    await client.post('/auth/logout');
}

export async function getCurrentUser(): Promise<CurrentUserResponse> {
    const { data } = await client.get<CurrentUserResponse>('/auth/me');
    return data;
}
