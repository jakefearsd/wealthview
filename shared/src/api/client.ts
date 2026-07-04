import axios, {
    type AxiosError,
    type AxiosInstance,
    type AxiosRequestConfig,
    type InternalAxiosRequestConfig,
} from 'axios';
import type { AuthTransport, MobileAuthResponse } from './types';

/**
 * Configuration handed to {@link createApiClient}.
 *
 * The factory is dependency-injected: callers provide closures that read the
 * current access/refresh token from whatever storage is appropriate to the
 * platform (`react-native-keychain` on mobile, HttpOnly cookies in the
 * browser — for browser the closures simply return null because the cookies
 * travel automatically).
 */
export interface ApiClientConfig {
    /** Absolute API root, e.g. `https://wealthview.example.com/api/v1`. */
    baseURL: string;
    /**
     * Either `bearer` (mobile, `Authorization: Bearer <token>`) or `cookie`
     * (browser, HttpOnly cookies set by the backend). The transport switches
     * BOTH the request shape AND which refresh endpoint is called.
     */
    transport: AuthTransport;
    /**
     * Returns the current in-memory access token (bearer) or null if none is
     * cached. Cookie transport implementations may return null unconditionally.
     */
    getAccessToken: () => string | null;
    /**
     * Returns the current refresh token (bearer) or null if none is stored.
     * Cookie transport implementations may return null — the cookie is sent
     * automatically by the browser.
     */
    getRefreshToken: () => string | null;
    /**
     * Invoked after a successful token refresh with the new pair so the caller
     * can persist them. Cookie transport callers can no-op this; the server
     * sets fresh HttpOnly cookies on the refresh response.
     */
    onTokensRefreshed: (tokens: MobileAuthResponse) => void;
    /**
     * Invoked when refresh fails (or no refresh token is available on a 401)
     * — the caller should clear local auth state and route the user to the
     * login screen.
     */
    onAuthFailed: () => void;
    /**
     * URL suffixes whose 401 responses must NOT trigger a refresh attempt.
     * A 401 from a matching request rejects immediately — no refresh call,
     * no {@link onAuthFailed}. Use this for the auth endpoints themselves:
     * refreshing on a failed `/auth/refresh` would loop, and refreshing on a
     * failed `/auth/login` is pointless (the form handles the error).
     * Matched with `url.endsWith(path)`. Optional; defaults to no exclusions.
     */
    skipRefreshPaths?: string[];
}

interface RetryFlag {
    _wvRetried?: boolean;
}

/**
 * Builds a configured axios instance. Each call returns a brand-new instance
 * with its own interceptors — no module-level singletons. That matters for the
 * mobile app where the user can change the server URL at runtime: the new URL
 * gets a fresh client and the old one is discarded.
 */
export function createApiClient(config: ApiClientConfig): AxiosInstance {
    const isBearer = config.transport === 'bearer';
    const skipRefreshPaths = config.skipRefreshPaths ?? [];

    function isRefreshExempt(url: string | undefined): boolean {
        if (!url) {
            return false;
        }
        return skipRefreshPaths.some((path) => url.endsWith(path));
    }

    const client = axios.create({
        baseURL: config.baseURL,
        headers: { 'Content-Type': 'application/json' },
        withCredentials: !isBearer,
    });

    // ---- request: attach the bearer token (if any) ----
    if (isBearer) {
        client.interceptors.request.use((req) => {
            const token = config.getAccessToken();
            if (token) {
                req.headers.set('Authorization', `Bearer ${token}`);
            }
            return req;
        });
    }

    // ---- response: refresh-on-401, single retry ----
    let refreshInFlight: Promise<void> | null = null;

    async function performRefresh(): Promise<void> {
        if (isBearer) {
            const refreshToken = config.getRefreshToken();
            if (!refreshToken) {
                throw new Error('No refresh token available');
            }
            const { data } = await axios.post<MobileAuthResponse>(
                `${config.baseURL}/auth/token/refresh`,
                { refresh_token: refreshToken },
                { headers: { 'Content-Type': 'application/json' } },
            );
            config.onTokensRefreshed(data);
        } else {
            // Cookie transport: server reads the refresh_token cookie; we send
            // no body but must keep credentials so the cookie travels.
            await axios.post(`${config.baseURL}/auth/refresh`, null, {
                withCredentials: true,
            });
        }
    }

    client.interceptors.response.use(
        (response) => response,
        async (error: AxiosError) => {
            const original = error.config as
                | (InternalAxiosRequestConfig & RetryFlag)
                | undefined;
            if (
                !original ||
                error.response?.status !== 401 ||
                original._wvRetried ||
                isRefreshExempt(original.url)
            ) {
                return Promise.reject(error);
            }
            original._wvRetried = true;

            // Coalesce concurrent refreshes — only one /refresh call in flight.
            if (!refreshInFlight) {
                refreshInFlight = performRefresh()
                    .catch((refreshErr) => {
                        config.onAuthFailed();
                        throw refreshErr;
                    })
                    .finally(() => {
                        refreshInFlight = null;
                    });
            }

            try {
                await refreshInFlight;
            } catch {
                return Promise.reject(error);
            }
            return client(original as AxiosRequestConfig);
        },
    );

    return client;
}
