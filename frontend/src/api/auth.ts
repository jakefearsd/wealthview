import axios from 'axios';
import client from './client';
import type { AuthResponse, CurrentUserResponse, LoginRequest, RegisterRequest } from '../types/auth';

const authClient = axios.create({
    baseURL: '/api/v1/auth',
    headers: { 'Content-Type': 'application/json' },
});

export async function login(request: LoginRequest): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/login', request);
    return data;
}

export async function register(request: RegisterRequest): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/register', request);
    return data;
}

export async function refresh(refreshToken: string): Promise<AuthResponse> {
    const { data } = await authClient.post<AuthResponse>('/refresh', {
        refresh_token: refreshToken,
    });
    return data;
}

export async function getCurrentUser(): Promise<CurrentUserResponse> {
    const { data } = await client.get<CurrentUserResponse>('/auth/me');
    return data;
}
