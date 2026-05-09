import type { AxiosInstance } from 'axios';
import type {
    AuthTransport,
    LoginOutcome,
    LoginRequest,
    MeResponse,
    MfaRequiredResponse,
    MobileAuthResponse,
    RegisterRequest,
} from './types';

export interface AuthApi {
    /** Submit credentials. Either yields a token pair or an MFA challenge. */
    login(request: LoginRequest): Promise<LoginOutcome>;
    /** Create a new account using an invite code. Same outcome shape as login. */
    register(request: RegisterRequest): Promise<LoginOutcome>;
    /**
     * Trade a refresh token for a fresh pair (bearer transport) or refresh
     * the auth cookies (cookie transport). The bearer signature requires the
     * caller to pass the current refresh token; the cookie signature ignores
     * any argument the caller supplies.
     */
    refresh(refreshToken?: string): Promise<MobileAuthResponse | void>;
    /** Server-side: revoke the current session. Local cleanup is the caller's job. */
    logout(): Promise<void>;
    /** Verify the current credentials and learn who we are. */
    getCurrentUser(): Promise<MeResponse>;
}

interface AuthRoutes {
    login: string;
    register: string;
    refresh: string;
    logout: string;
}

function routesFor(transport: AuthTransport): AuthRoutes {
    return transport === 'bearer'
        ? {
              login: '/auth/token/login',
              register: '/auth/token/register',
              refresh: '/auth/token/refresh',
              logout: '/auth/token/logout',
          }
        : {
              login: '/auth/login',
              register: '/auth/register',
              refresh: '/auth/refresh',
              logout: '/auth/logout',
          };
}

function isMfaRequired(body: unknown): body is MfaRequiredResponse {
    return (
        typeof body === 'object' &&
        body !== null &&
        (body as { mfa_required?: unknown }).mfa_required === true &&
        typeof (body as { mfa_token?: unknown }).mfa_token === 'string'
    );
}

function asLoginOutcome(body: unknown): LoginOutcome {
    if (isMfaRequired(body)) {
        return { type: 'mfa_required', mfa_token: body.mfa_token };
    }
    return { type: 'tokens', tokens: body as MobileAuthResponse };
}

/**
 * Builds typed wrappers around the auth endpoints. The transport switches
 * between the cookie-based browser endpoints (`/auth/login`) and the
 * Bearer-based mobile endpoints (`/auth/token/login`). Every wrapper round-
 * trips through the supplied {@link AxiosInstance} so callers can attach
 * cross-cutting behaviour (interceptors, retries, headers) at construction
 * time and reuse it for every call.
 */
export function createAuthApi(client: AxiosInstance, transport: AuthTransport): AuthApi {
    const routes = routesFor(transport);

    return {
        async login(request) {
            const { data } = await client.post(routes.login, request);
            return asLoginOutcome(data);
        },
        async register(request) {
            const { data } = await client.post(routes.register, request);
            return asLoginOutcome(data);
        },
        async refresh(refreshToken?: string) {
            if (transport === 'bearer') {
                if (!refreshToken) {
                    throw new Error('refresh() requires a refresh_token in bearer mode');
                }
                const { data } = await client.post<MobileAuthResponse>(routes.refresh, {
                    refresh_token: refreshToken,
                });
                return data;
            }
            await client.post(routes.refresh, null);
            return undefined;
        },
        async logout() {
            await client.post(routes.logout);
        },
        async getCurrentUser() {
            const { data } = await client.get<MeResponse>('/auth/me');
            return data;
        },
    };
}
