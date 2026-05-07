import axios from 'axios';
import client from './client';
import type { AuthResponse, CurrentUserResponse, LoginRequest, RegisterRequest } from '../types/auth';

const authClient = axios.create({
    baseURL: '/api/v1/auth',
    headers: { 'Content-Type': 'application/json' },
    withCredentials: true,
});

export async function login(request: LoginRequest): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/login', request);
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
