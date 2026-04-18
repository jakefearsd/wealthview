import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import axios from 'axios';

vi.mock('../utils/storage', () => ({
    getAccessToken: vi.fn(),
    getRefreshToken: vi.fn(),
    setTokens: vi.fn(),
    clearTokens: vi.fn(),
}));

import {
    getAccessToken,
    getRefreshToken,
    setTokens,
    clearTokens,
} from '../utils/storage';

const mockGetAccessToken = vi.mocked(getAccessToken);
const mockGetRefreshToken = vi.mocked(getRefreshToken);
const mockSetTokens = vi.mocked(setTokens);
const mockClearTokens = vi.mocked(clearTokens);

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
        data: { message: 'Unauthorized' },
        status: 401,
        statusText: 'Unauthorized',
        headers: {},
        config,
    };
}

/**
 * Import a fresh copy of the client module so the module-level `isRefreshing` /
 * `failedQueue` state doesn't leak between tests.
 */
async function freshClient() {
    vi.resetModules();
    const { default: client } = await import('./client');
    return client;
}

describe('api client — request interceptor', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('attaches Authorization header when access token is present', async () => {
        mockGetAccessToken.mockReturnValue('abc.token');
        const client = await freshClient();
        const captured: InternalAxiosRequestConfig[] = [];
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            captured.push(config);
            return ok(config);
        }) as AxiosAdapter;

        await client.get('/accounts');

        expect(captured[0].headers.Authorization).toBe('Bearer abc.token');
    });

    it('omits Authorization header when no token is stored', async () => {
        mockGetAccessToken.mockReturnValue(null);
        const client = await freshClient();
        const captured: InternalAxiosRequestConfig[] = [];
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            captured.push(config);
            return ok(config);
        }) as AxiosAdapter;

        await client.get('/accounts');

        expect(captured[0].headers.Authorization).toBeUndefined();
    });

    it('uses /api/v1 as the baseURL', async () => {
        const client = await freshClient();
        expect(client.defaults.baseURL).toBe('/api/v1');
    });
});

describe('api client — 401 response handling', () => {
    let originalAdapter: AxiosAdapter | undefined;
    let originalLocation: Location;

    beforeEach(() => {
        vi.clearAllMocks();
        mockGetAccessToken.mockReturnValue('access-old');
        originalAdapter = axios.defaults.adapter as AxiosAdapter | undefined;
        originalLocation = window.location;
        // Allow assigning window.location.href without navigation
        // @ts-expect-error overriding readonly for test
        delete window.location;
        // @ts-expect-error test shim
        window.location = { href: '' } as Location;
    });

    afterEach(() => {
        axios.defaults.adapter = originalAdapter;
        // @ts-expect-error restore
        window.location = originalLocation;
    });

    it('rejects and redirects to /login when no refresh token is available', async () => {
        mockGetRefreshToken.mockReturnValue(null);
        const client = await freshClient();
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({
                response: unauthorized(config),
                config,
            })) as AxiosAdapter;

        await expect(client.get('/accounts')).rejects.toBeDefined();
        expect(mockClearTokens).toHaveBeenCalledTimes(1);
        expect(window.location.href).toBe('/login');
    });

    it('refreshes the token and replays the original request on 401', async () => {
        mockGetRefreshToken.mockReturnValue('refresh-old');
        const client = await freshClient();

        let callCount = 0;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            callCount++;
            if (callCount === 1) {
                // First call: return 401
                return Promise.reject({ response: unauthorized(config), config });
            }
            // Retry call: succeed
            return ok(config, { ok: true });
        }) as AxiosAdapter;

        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            // refresh endpoint
            return ok(config, { access_token: 'access-new', refresh_token: 'refresh-new' });
        }) as AxiosAdapter;

        const response = await client.get('/accounts');

        expect(response.data).toEqual({ ok: true });
        expect(mockSetTokens).toHaveBeenCalledWith('access-new', 'refresh-new');
        expect(callCount).toBe(2);
    });

    it('retries the original request with the refreshed Bearer token', async () => {
        // Simulate real storage: setTokens updates what getAccessToken returns.
        let currentAccess = 'access-old';
        mockGetAccessToken.mockImplementation(() => currentAccess);
        mockGetRefreshToken.mockReturnValue('refresh-old');
        mockSetTokens.mockImplementation((access: string) => { currentAccess = access; });

        const client = await freshClient();

        let firstCallHeaders: unknown = null;
        let retryHeaders: unknown = null;
        let callCount = 0;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            callCount++;
            if (callCount === 1) {
                firstCallHeaders = config.headers.Authorization;
                return Promise.reject({ response: unauthorized(config), config });
            }
            retryHeaders = config.headers.Authorization;
            return ok(config);
        }) as AxiosAdapter;

        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            ok(config, { access_token: 'access-new', refresh_token: 'refresh-new' })) as AxiosAdapter;

        await client.get('/accounts');

        expect(firstCallHeaders).toBe('Bearer access-old');
        expect(retryHeaders).toBe('Bearer access-new');
    });

    it('clears tokens and redirects to /login when refresh call fails', async () => {
        mockGetRefreshToken.mockReturnValue('refresh-old');
        const client = await freshClient();
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        axios.defaults.adapter = (async () =>
            Promise.reject(new Error('refresh down'))) as AxiosAdapter;

        await expect(client.get('/accounts')).rejects.toBeDefined();
        expect(mockClearTokens).toHaveBeenCalledTimes(1);
        expect(window.location.href).toBe('/login');
    });

    it('does not attempt a second refresh for a retried request that 401s again', async () => {
        mockGetRefreshToken.mockReturnValue('refresh-old');
        const client = await freshClient();

        let refreshCalls = 0;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config, { access_token: 'access-new', refresh_token: 'refresh-new' });
        }) as AxiosAdapter;

        // Both the initial request and the retry return 401. The second 401 carries _retry=true
        // so the interceptor must not loop back into refresh.
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({ response: unauthorized(config), config })) as AxiosAdapter;

        await expect(client.get('/accounts')).rejects.toBeDefined();
        expect(refreshCalls).toBe(1);
    });

    it('passes through non-401 errors without triggering refresh', async () => {
        mockGetRefreshToken.mockReturnValue('refresh-old');
        const client = await freshClient();

        let refreshCalls = 0;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            return ok(config);
        }) as AxiosAdapter;

        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) =>
            Promise.reject({
                response: {
                    data: { message: 'server error' },
                    status: 500,
                    statusText: 'Internal',
                    headers: {},
                    config,
                },
                config,
            })) as AxiosAdapter;

        await expect(client.get('/accounts')).rejects.toBeDefined();
        expect(refreshCalls).toBe(0);
        expect(mockClearTokens).not.toHaveBeenCalled();
    });
});

describe('api client — concurrent 401 queueing', () => {
    let originalAdapter: AxiosAdapter | undefined;
    let originalLocation: Location;

    beforeEach(() => {
        vi.clearAllMocks();
        mockGetAccessToken.mockReturnValue('access-old');
        mockGetRefreshToken.mockReturnValue('refresh-old');
        originalAdapter = axios.defaults.adapter as AxiosAdapter | undefined;
        originalLocation = window.location;
        // @ts-expect-error overriding readonly for test
        delete window.location;
        // @ts-expect-error test shim
        window.location = { href: '' } as Location;
    });

    afterEach(() => {
        axios.defaults.adapter = originalAdapter;
        // @ts-expect-error restore
        window.location = originalLocation;
    });

    it('queues concurrent 401s and replays them once refresh completes', async () => {
        let currentAccess = 'access-old';
        mockGetAccessToken.mockImplementation(() => currentAccess);
        mockSetTokens.mockImplementation((access: string) => { currentAccess = access; });

        const client = await freshClient();

        const retryHeaders: string[] = [];
        let callCount = 0;
        client.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            callCount++;
            if (callCount <= 2) {
                return Promise.reject({ response: unauthorized(config), config });
            }
            retryHeaders.push(config.headers.Authorization as string);
            return ok(config, { ok: true });
        }) as AxiosAdapter;

        let refreshCalls = 0;
        axios.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
            refreshCalls++;
            await Promise.resolve();
            return ok(config, { access_token: 'access-new', refresh_token: 'refresh-new' });
        }) as AxiosAdapter;

        const [r1, r2] = await Promise.all([client.get('/a'), client.get('/b')]);

        expect(r1.data).toEqual({ ok: true });
        expect(r2.data).toEqual({ ok: true });
        expect(refreshCalls).toBe(1);
        expect(retryHeaders).toHaveLength(2);
        expect(retryHeaders.every(h => h === 'Bearer access-new')).toBe(true);
    });
});
