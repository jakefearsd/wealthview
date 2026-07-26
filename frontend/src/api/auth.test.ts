import { describe, it, expect, vi, beforeEach } from 'vitest';

// auth.ts uses both a private axios instance (via axios.create) for /auth
// endpoints that don't need auto-refresh, AND the shared `client` for the
// authenticated routes. Mock both edges so we can assert URLs/methods/bodies
// without touching the network. The axios factory needs its `vi.fn()` via
// vi.hoisted (factories run before imports); the './client' edge instead
// uses the shared automock (src/api/__mocks__/client.ts) via a factory-less
// vi.mock, same as the other api/*.test.ts files.
const hoisted = vi.hoisted(() => ({
    authPost: vi.fn(),
}));

vi.mock('axios', async () => {
    const actual = await vi.importActual<typeof import('axios')>('axios');
    return {
        ...actual,
        default: {
            ...actual.default,
            create: vi.fn(() => ({
                post: hoisted.authPost,
            })),
        },
    };
});

vi.mock('./client');

import client from './client';

const mocks = {
    authPost: hoisted.authPost,
    clientPost: vi.mocked(client.post),
    clientGet: vi.mocked(client.get),
};

import { login, register, refresh, logout, getCurrentUser } from './auth';
import type { AuthResponse, CurrentUserResponse } from '../types/auth';

const SAMPLE_AUTH: AuthResponse = {
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@wealthview.local',
    role: 'admin',
};

const SAMPLE_USER: CurrentUserResponse = {
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@wealthview.local',
    role: 'admin',
};

describe('api/auth', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    describe('login', () => {
        it('POSTs to /login on the auth client with the credentials body', async () => {
            mocks.authPost.mockResolvedValue({ data: SAMPLE_AUTH });

            const result = await login({ email: 'demo@x', password: 'pw' });

            expect(result).toEqual(SAMPLE_AUTH);
            expect(mocks.authPost).toHaveBeenCalledWith('/login', {
                email: 'demo@x',
                password: 'pw',
            });
        });

        it('propagates server errors', async () => {
            mocks.authPost.mockRejectedValue(new Error('Bad credentials'));

            await expect(login({ email: 'a', password: 'b' })).rejects.toThrow('Bad credentials');
        });
    });

    describe('register', () => {
        it('POSTs to /register with the request body', async () => {
            mocks.authPost.mockResolvedValue({ data: SAMPLE_AUTH });

            const result = await register({
                email: 'new@x',
                password: 'pw',
                invite_code: 'abc',
            });

            expect(result).toEqual(SAMPLE_AUTH);
            expect(mocks.authPost).toHaveBeenCalledWith('/register', {
                email: 'new@x',
                password: 'pw',
                invite_code: 'abc',
            });
        });
    });

    describe('refresh', () => {
        it('POSTs to /refresh with a null body — server reads the refresh_token cookie', async () => {
            mocks.authPost.mockResolvedValue({ data: SAMPLE_AUTH });

            const result = await refresh();

            expect(result).toEqual(SAMPLE_AUTH);
            expect(mocks.authPost).toHaveBeenCalledWith('/refresh', null);
        });
    });

    describe('logout', () => {
        it('POSTs to /auth/logout via the shared authenticated client', async () => {
            mocks.clientPost.mockResolvedValue({ data: undefined });

            await logout();

            expect(mocks.clientPost).toHaveBeenCalledWith('/auth/logout');
            expect(mocks.authPost).not.toHaveBeenCalled();
        });
    });

    describe('getCurrentUser', () => {
        it('GETs /auth/me via the shared authenticated client and returns the body', async () => {
            mocks.clientGet.mockResolvedValue({ data: SAMPLE_USER });

            const user = await getCurrentUser();

            expect(user).toEqual(SAMPLE_USER);
            expect(mocks.clientGet).toHaveBeenCalledWith('/auth/me');
        });
    });
});
