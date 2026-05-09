import * as Keychain from 'react-native-keychain';
import type { MobileAuthResponse, MeResponse } from '@wealthview/shared';

/**
 * Service keys used for keychain-backed storage. Each service stores a
 * single (username, password) pair; we use the username slot for sentinels
 * ("token", "json") and the password slot for the actual value. This keeps
 * the API symmetric across token / identity / URL storage.
 */
const SERVICE_REFRESH = 'com.wealthview.mobile.refreshToken';
const SERVICE_ACCESS = 'com.wealthview.mobile.accessToken';
const SERVICE_IDENTITY = 'com.wealthview.mobile.identity';

async function readPassword(service: string): Promise<string | null> {
    const result = await Keychain.getGenericPassword({ service });
    if (!result) {
        return null;
    }
    return result.password;
}

async function writePassword(service: string, value: string, slot: string): Promise<void> {
    await Keychain.setGenericPassword(slot, value, { service });
}

async function deletePassword(service: string): Promise<void> {
    await Keychain.resetGenericPassword({ service });
}

export const tokenStorage = {
    async getRefreshToken(): Promise<string | null> {
        return readPassword(SERVICE_REFRESH);
    },
    async setRefreshToken(token: string): Promise<void> {
        await writePassword(SERVICE_REFRESH, token, 'refresh');
    },
    async getAccessToken(): Promise<string | null> {
        return readPassword(SERVICE_ACCESS);
    },
    async setAccessToken(token: string): Promise<void> {
        await writePassword(SERVICE_ACCESS, token, 'access');
    },
    async getIdentity(): Promise<MeResponse | null> {
        const raw = await readPassword(SERVICE_IDENTITY);
        if (!raw) {
            return null;
        }
        try {
            return JSON.parse(raw) as MeResponse;
        } catch {
            return null;
        }
    },
    async setIdentity(identity: MeResponse): Promise<void> {
        await writePassword(SERVICE_IDENTITY, JSON.stringify(identity), 'identity');
    },
    /** Persist the access + refresh pair AND the identity in one call. */
    async persistTokens(tokens: MobileAuthResponse): Promise<void> {
        await Promise.all([
            writePassword(SERVICE_ACCESS, tokens.access_token, 'access'),
            writePassword(SERVICE_REFRESH, tokens.refresh_token, 'refresh'),
            writePassword(
                SERVICE_IDENTITY,
                JSON.stringify({
                    user_id: tokens.user_id,
                    tenant_id: tokens.tenant_id,
                    email: tokens.email,
                    role: tokens.role,
                }),
                'identity',
            ),
        ]);
    },
    /** Wipes every WealthView-owned keychain entry (logout / forced re-auth). */
    async clear(): Promise<void> {
        await Promise.all([
            deletePassword(SERVICE_REFRESH),
            deletePassword(SERVICE_ACCESS),
            deletePassword(SERVICE_IDENTITY),
        ]);
    },
};
