import {
    createApiClient,
    createAuthApi,
    type AuthApi,
    type MobileAuthResponse,
} from '@wealthview/shared';
import type { AxiosInstance } from 'axios';
import { apiBaseUrl } from '../config/serverUrlStorage';

export interface MobileApiBundle {
    client: AxiosInstance;
    authApi: AuthApi;
}

export interface MobileApiOptions {
    /** Bare server URL (e.g. https://wealthview.example.com) — `/api/v1` is appended. */
    serverUrl: string;
    /** In-memory access-token cache. Re-derived from refresh on cold start. */
    accessToken: { current: string | null };
    /** Returns the durable refresh token from secure storage (sync read of cache). */
    getRefreshToken: () => string | null;
    /** Persist the rotated token pair after a successful refresh. */
    onTokensRefreshed: (tokens: MobileAuthResponse) => void | Promise<void>;
    /** Forced-logout signal: refresh failed or no refresh token exists. */
    onAuthFailed: () => void | Promise<void>;
}

/**
 * Builds a fresh axios client + auth API for the mobile app, parameterised
 * by the user-configured server URL and the provided in-memory token caches.
 *
 * The factory returns a NEW axios instance every call, so swapping server
 * URLs at runtime is just "discard the old bundle and call this again".
 */
export function buildMobileApi(options: MobileApiOptions): MobileApiBundle {
    const client = createApiClient({
        baseURL: apiBaseUrl(options.serverUrl),
        transport: 'bearer',
        getAccessToken: () => options.accessToken.current,
        getRefreshToken: options.getRefreshToken,
        onTokensRefreshed: (tokens) => {
            options.accessToken.current = tokens.access_token;
            void options.onTokensRefreshed(tokens);
        },
        onAuthFailed: () => {
            void options.onAuthFailed();
        },
    });

    const authApi = createAuthApi(client, 'bearer');
    return { client, authApi };
}
