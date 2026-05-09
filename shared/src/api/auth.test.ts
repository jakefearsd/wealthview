import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { AxiosInstance } from 'axios';
import { createAuthApi } from './auth';
import type { MobileAuthResponse, MeResponse } from './types';

const SAMPLE_TOKENS: MobileAuthResponse = {
    access_token: 'at',
    refresh_token: 'rt',
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@x',
    role: 'member',
};

const SAMPLE_ME: MeResponse = {
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@x',
    role: 'member',
};

function makeFakeClient() {
    const post = vi.fn();
    const get = vi.fn();
    return {
        client: { post, get } as unknown as AxiosInstance,
        post,
        get,
    };
}

describe('createAuthApi — bearer transport (mobile)', () => {
    let post: ReturnType<typeof vi.fn>;
    let get: ReturnType<typeof vi.fn>;
    let api: ReturnType<typeof createAuthApi>;

    beforeEach(() => {
        const f = makeFakeClient();
        post = f.post;
        get = f.get;
        api = createAuthApi(f.client, 'bearer');
    });

    describe('login', () => {
        it('POSTs to /auth/token/login and returns a tokens outcome on success', async () => {
            post.mockResolvedValue({ data: SAMPLE_TOKENS });

            const outcome = await api.login({ email: 'demo@x', password: 'pw' });

            expect(post).toHaveBeenCalledWith('/auth/token/login', {
                email: 'demo@x',
                password: 'pw',
            });
            expect(outcome).toEqual({ type: 'tokens', tokens: SAMPLE_TOKENS });
        });

        it('returns an mfa_required outcome when the server signals MFA', async () => {
            post.mockResolvedValue({
                data: { mfa_required: true, mfa_token: 'mfa-jwt' },
            });

            const outcome = await api.login({ email: 'demo@x', password: 'pw' });

            expect(outcome).toEqual({ type: 'mfa_required', mfa_token: 'mfa-jwt' });
        });

        it('forwards the optional device_label', async () => {
            post.mockResolvedValue({ data: SAMPLE_TOKENS });

            await api.login({ email: 'demo@x', password: 'pw', device_label: 'Pixel 8' });

            expect(post).toHaveBeenCalledWith('/auth/token/login', {
                email: 'demo@x',
                password: 'pw',
                device_label: 'Pixel 8',
            });
        });
    });

    describe('register', () => {
        it('POSTs to /auth/token/register with the request body', async () => {
            post.mockResolvedValue({ data: SAMPLE_TOKENS });

            const outcome = await api.register({
                email: 'new@x',
                password: 'pw',
                invite_code: 'abc',
            });

            expect(post).toHaveBeenCalledWith('/auth/token/register', {
                email: 'new@x',
                password: 'pw',
                invite_code: 'abc',
            });
            expect(outcome).toEqual({ type: 'tokens', tokens: SAMPLE_TOKENS });
        });
    });

    describe('refresh', () => {
        it('POSTs to /auth/token/refresh with the refresh token in the body', async () => {
            post.mockResolvedValue({ data: SAMPLE_TOKENS });

            const tokens = await api.refresh('rt-xyz');

            expect(post).toHaveBeenCalledWith('/auth/token/refresh', {
                refresh_token: 'rt-xyz',
            });
            expect(tokens).toEqual(SAMPLE_TOKENS);
        });
    });

    describe('logout', () => {
        it('POSTs to /auth/token/logout with no body', async () => {
            post.mockResolvedValue({ data: undefined });

            await api.logout();

            expect(post).toHaveBeenCalledWith('/auth/token/logout');
        });
    });

    describe('getCurrentUser', () => {
        it('GETs /auth/me and returns the body', async () => {
            get.mockResolvedValue({ data: SAMPLE_ME });

            const me = await api.getCurrentUser();

            expect(get).toHaveBeenCalledWith('/auth/me');
            expect(me).toEqual(SAMPLE_ME);
        });
    });
});

describe('createAuthApi — cookie transport (web)', () => {
    let post: ReturnType<typeof vi.fn>;
    let get: ReturnType<typeof vi.fn>;
    let api: ReturnType<typeof createAuthApi>;

    beforeEach(() => {
        const f = makeFakeClient();
        post = f.post;
        get = f.get;
        api = createAuthApi(f.client, 'cookie');
    });

    it('login posts to /auth/login (no /token/ segment)', async () => {
        post.mockResolvedValue({ data: SAMPLE_TOKENS });

        await api.login({ email: 'demo@x', password: 'pw' });

        expect(post).toHaveBeenCalledWith('/auth/login', {
            email: 'demo@x',
            password: 'pw',
        });
    });

    it('refresh posts to /auth/refresh with no body — server reads the cookie', async () => {
        post.mockResolvedValue({ data: SAMPLE_TOKENS });

        await api.refresh();

        expect(post).toHaveBeenCalledWith('/auth/refresh', null);
    });

    it('logout posts to /auth/logout', async () => {
        post.mockResolvedValue({ data: undefined });

        await api.logout();

        expect(post).toHaveBeenCalledWith('/auth/logout');
    });

    it('getCurrentUser GETs /auth/me (same on both transports)', async () => {
        get.mockResolvedValue({ data: SAMPLE_ME });

        await api.getCurrentUser();

        expect(get).toHaveBeenCalledWith('/auth/me');
    });
});
