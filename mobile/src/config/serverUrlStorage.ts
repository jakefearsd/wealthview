import * as Keychain from 'react-native-keychain';

const SERVICE_SERVER_URL = 'com.wealthview.mobile.serverUrl';

/**
 * The server URL isn't really sensitive but it's co-located with the auth
 * tokens for two reasons: a single storage primitive to mock in tests, and
 * users with biometric-locked devices should have a consistent unlock
 * experience across all WealthView state.
 */
export const serverUrlStorage = {
    async get(): Promise<string | null> {
        const result = await Keychain.getGenericPassword({ service: SERVICE_SERVER_URL });
        if (!result) {
            return null;
        }
        return result.password;
    },
    async set(url: string): Promise<void> {
        await Keychain.setGenericPassword('serverUrl', url, { service: SERVICE_SERVER_URL });
    },
    async clear(): Promise<void> {
        await Keychain.resetGenericPassword({ service: SERVICE_SERVER_URL });
    },
};

/** Returns true if `value` parses as an http(s) URL with a host. */
export function isValidServerUrl(value: string): boolean {
    if (!value || typeof value !== 'string') {
        return false;
    }
    try {
        const url = new URL(value.trim());
        if (url.protocol !== 'http:' && url.protocol !== 'https:') {
            return false;
        }
        return url.hostname.length > 0;
    } catch {
        return false;
    }
}

/**
 * Strip a trailing slash so the API base URL we hand to the shared client
 * concatenates cleanly with `/api/v1` paths.
 */
export function normalizeServerUrl(value: string): string {
    return value.trim().replace(/\/+$/, '');
}

/** Build the API root from a stored server URL. */
export function apiBaseUrl(serverUrl: string): string {
    return `${normalizeServerUrl(serverUrl)}/api/v1`;
}
