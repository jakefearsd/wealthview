import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import axios from 'axios';
import { createApiClient } from './client';
import type { MobileAuthResponse } from './types';

function ok(config: InternalAxiosRequestConfig, body: unknown = {}): AxiosResponse {
    return {
        data: body,
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
    };
}

function unauthorized(config: InternalAxiosRequestConfig): AxiosResponse {
    return {
        data: { error: 'UNAUTHORIZED', message: 'Token expired', status: 401 },
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
    };
}

const REFRESHED: MobileAuthResponse = {
    access_token: 'fresh-at',
    refresh_token: 'fresh-rt',
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@x',
    role: 'member',
};

describe('createApiClient — bearer transport', () => {
    let originalAxiosAdapter: AxiosAdapter | undefined;

    beforeEach(() => {
        originalAxiosAdapter = axios.defaults.adapter as AxiosAdapter | undefined;
    });

    afterEach(() => {
        axios.defaults.adapter = originalAxiosAdapter;
    });

    it('uses the configured baseURL', () => {
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        expect(client.defaults.baseURL).toBe('https://api.example.com');
    });

    it('attaches Authorization: Bearer <token> from getAccessToken()', async () => {
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'access-123',
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        const captured: InternalAxiosRequestConfig[] = [];
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            captured.push(config);
            return ok(config);
        }) as AxiosAdapter;

        await client.get('/auth/me');

        expect(captured[0].headers.Authorization).toBe('Bearer access-123');
    });

    it('omits the Authorization header when no access token is available', async () => {
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        const captured: InternalAxiosRequestConfig[] = [];
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            captured.push(config);
            return ok(config);
        }) as AxiosAdapter;

        await client.get('/public');

        expect(captured[0].headers.Authorization).toBeUndefined();
    });

    it('does NOT enable withCredentials in bearer mode', () => {
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        expect(client.defaults.withCredentials).toBe(false);
    });

    it('on 401: calls /auth/token/refresh with the refresh token, persists new tokens, and replays the original request', async () => {
        let currentAccess = 'stale-at';
        let storedTokens: MobileAuthResponse | null = null;

        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => currentAccess,
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: (pair) => {
                storedTokens = pair;
                currentAccess = pair.access_token;
            },
            onAuthFailed: () => {},
        });

        const seen: { url?: string; auth?: unknown }[] = [];
        let firstCall = true;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            seen.push({ url: config.url, auth: config.headers.Authorization });
            if (firstCall) {
                firstCall = false;
                return Promise.reject({ response: unauthorized(config), config });
            }
            return ok(config, { protected: 'data' });
        }) as AxiosAdapter;

        let refreshUrl: string | undefined;
        let refreshBody: unknown;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshUrl = config.url;
            refreshBody =
                typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
            return ok(config, REFRESHED);
        }) as AxiosAdapter;

        try {
            const response = await client.get('/accounts');

            expect(response.data).toEqual({ protected: 'data' });
            expect(refreshUrl).toBe('https://api.example.com/auth/token/refresh');
            expect(refreshBody).toEqual({ refresh_token: 'refresh-abc' });
            expect(storedTokens).toEqual(REFRESHED);
            // Replay used the new access token.
            expect(seen[0].auth).toBe('Bearer stale-at');
            expect(seen[1].auth).toBe('Bearer fresh-at');
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('on refresh failure: calls onAuthFailed and rejects with the original 401', async () => {
        const onAuthFailed = vi.fn();

        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'stale-at',
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: () => {},
            onAuthFailed,
        });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async () =>
            Promise.reject(new Error('refresh down'))) as AxiosAdapter;

        try {
            await expect(client.get('/accounts')).rejects.toBeDefined();
            expect(onAuthFailed).toHaveBeenCalledTimes(1);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('does not retry refresh on a request that has already been retried', async () => {
        let refreshCalls = 0;
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'stale-at',
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, REFRESHED);
        }) as AxiosAdapter;

        try {
            await expect(client.get('/accounts')).rejects.toBeDefined();
            expect(refreshCalls).toBe(1);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('passes through non-401 errors without triggering refresh', async () => {
        let refreshCalls = 0;
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'stale-at',
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({
                response: {
                    data: { error: 'INTERNAL', message: 'oops', status: 500 },
                    status: 500,
                    statusText: 'Server Error',
                    headers: {},
                    config,
                },
                config,
            })) as AxiosAdapter;

        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, REFRESHED);
        }) as AxiosAdapter;

        try {
            await expect(client.get('/accounts')).rejects.toBeDefined();
            expect(refreshCalls).toBe(0);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('skips refresh on 401 when no refresh token is available', async () => {
        const onAuthFailed = vi.fn();
        let refreshCalls = 0;
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'stale-at',
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed,
        });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, REFRESHED);
        }) as AxiosAdapter;

        try {
            await expect(client.get('/accounts')).rejects.toBeDefined();
            expect(refreshCalls).toBe(0);
            expect(onAuthFailed).toHaveBeenCalledTimes(1);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });
});

describe('createApiClient — cookie transport', () => {
    it('does NOT attach an Authorization header even if getAccessToken returns one', async () => {
        const client = createApiClient({
            baseURL: '/api/v1',
            transport: 'cookie',
            getAccessToken: () => 'should-not-leak',
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        const captured: InternalAxiosRequestConfig[] = [];
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            captured.push(config);
            return ok(config);
        }) as AxiosAdapter;

        await client.get('/accounts');

        expect(captured[0].headers.Authorization).toBeUndefined();
    });

    it('enables withCredentials so cookies travel with each request', () => {
        const client = createApiClient({
            baseURL: '/api/v1',
            transport: 'cookie',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        expect(client.defaults.withCredentials).toBe(true);
    });

    it('on 401: calls /auth/refresh with NO body (cookie-driven) and replays the request', async () => {
        const client = createApiClient({
            baseURL: '/api/v1',
            transport: 'cookie',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        let firstCall = true;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            if (firstCall) {
                firstCall = false;
                return Promise.reject({ response: unauthorized(config), config });
            }
            return ok(config, { ok: true });
        }) as AxiosAdapter;

        let refreshUrl: string | undefined;
        let refreshBody: unknown;
        let refreshWithCredentials = false;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshUrl = config.url;
            refreshBody = config.data;
            refreshWithCredentials = config.withCredentials === true;
            return ok(config, {});
        }) as AxiosAdapter;

        try {
            const response = await client.get('/accounts');
            expect(response.data).toEqual({ ok: true });
            expect(refreshUrl).toBe('/api/v1/auth/refresh');
            expect(refreshBody == null || refreshBody === '').toBe(true);
            expect(refreshWithCredentials).toBe(true);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });
});

describe('createApiClient — skipRefreshPaths', () => {
    const SKIP_PATHS = ['/auth/refresh', '/auth/login', '/auth/register'];

    function cookieClient(overrides: { onAuthFailed?: () => void } = {}) {
        return createApiClient({
            baseURL: '/api/v1',
            transport: 'cookie',
            getAccessToken: () => null,
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: overrides.onAuthFailed ?? (() => {}),
            skipRefreshPaths: SKIP_PATHS,
        });
    }

    it('rejects a 401 from a skip path immediately without attempting refresh', async () => {
        const onAuthFailed = vi.fn();
        const client = cookieClient({ onAuthFailed });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        let refreshCalls = 0;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, {});
        }) as AxiosAdapter;

        try {
            await expect(client.post('/auth/login', {})).rejects.toBeDefined();
            expect(refreshCalls).toBe(0);
            expect(onAuthFailed).not.toHaveBeenCalled();
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('matches skip paths by URL suffix', async () => {
        const client = cookieClient();

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        let refreshCalls = 0;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, {});
        }) as AxiosAdapter;

        try {
            await expect(client.post('/auth/refresh', null)).rejects.toBeDefined();
            expect(refreshCalls).toBe(0);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('still refreshes 401s from paths NOT in skipRefreshPaths', async () => {
        const client = cookieClient();

        let firstCall = true;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            if (firstCall) {
                firstCall = false;
                return Promise.reject({ response: unauthorized(config), config });
            }
            return ok(config, { ok: true });
        }) as AxiosAdapter;

        let refreshCalls = 0;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, {});
        }) as AxiosAdapter;

        try {
            const response = await client.get('/auth/me');
            expect(response.data).toEqual({ ok: true });
            expect(refreshCalls).toBe(1);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });

    it('applies to bearer transport too', async () => {
        const client = createApiClient({
            baseURL: 'https://api.example.com',
            transport: 'bearer',
            getAccessToken: () => 'stale-at',
            getRefreshToken: () => 'refresh-abc',
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
            skipRefreshPaths: ['/auth/login'],
        });

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        let refreshCalls = 0;
        const originalAdapter = axios.defaults.adapter;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, REFRESHED);
        }) as AxiosAdapter;

        try {
            await expect(client.post('/auth/login', {})).rejects.toBeDefined();
            expect(refreshCalls).toBe(0);
        } finally {
            axios.defaults.adapter = originalAdapter;
        }
    });
});

describe('createApiClient — isolation', () => {
    it('is safe to call multiple times: each instance has its own interceptor state', async () => {
        const a = createApiClient({
            baseURL: 'https://a.example.com',
            transport: 'bearer',
            getAccessToken: () => 'a-tok',
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });
        const b = createApiClient({
            baseURL: 'https://b.example.com',
            transport: 'bearer',
            getAccessToken: () => 'b-tok',
            getRefreshToken: () => null,
            onTokensRefreshed: () => {},
            onAuthFailed: () => {},
        });

        const seenA: string[] = [];
        const seenB: string[] = [];
        a.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            seenA.push(String(config.headers.Authorization));
            return ok(config);
        }) as AxiosAdapter;
        b.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            seenB.push(String(config.headers.Authorization));
            return ok(config);
        }) as AxiosAdapter;

        await Promise.all([a.get('/x'), b.get('/y')]);

        expect(seenA).toEqual(['Bearer a-tok']);
        expect(seenB).toEqual(['Bearer b-tok']);
    });
});
