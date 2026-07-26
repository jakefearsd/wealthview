import React, {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useReducer,
    useRef,
} from 'react';
import { extractErrorMessage } from '@wealthview/shared';
import type { MeResponse, MobileAuthResponse } from '@wealthview/shared';
import { tokenStorage } from './tokenStorage';
import { serverUrlStorage } from '../config/serverUrlStorage';
import { buildMobileApi, type MobileApiBundle } from './apiClient';
import { buildDataApis, type DataApis } from '../api/apis';

export type AuthStatus =
    | 'restoring'
    | 'needs_server'
    | 'unauthenticated'
    | 'authenticated';

export interface AuthState {
    status: AuthStatus;
    serverUrl: string | null;
    identity: MeResponse | null;
    error: string | null;
}

export interface AuthContextValue extends AuthState {
    login(email: string, password: string): Promise<void>;
    logout(): Promise<void>;
    refreshMe(): Promise<void>;
    setServerUrl(url: string): Promise<void>;
    clearError(): void;
    /**
     * Returns the data-API wrappers (dashboard, accounts) backed by the
     * currently authenticated axios client. Throws if called before the
     * server URL is configured. Returns null if there's no API bundle yet
     * (e.g. on the unauthenticated branch where no API has been built).
     */
    getDataApis(): DataApis | null;
}

type Action =
    | { type: 'restore_complete'; payload: { serverUrl: string | null; identity: MeResponse | null; status: AuthStatus } }
    | { type: 'login_success'; identity: MeResponse }
    | { type: 'login_failure'; error: string }
    | { type: 'logout' }
    | { type: 'identity_updated'; identity: MeResponse }
    | { type: 'auth_failed' }
    | { type: 'server_url_set'; url: string }
    | { type: 'clear_error' };

const initial: AuthState = {
    status: 'restoring',
    serverUrl: null,
    identity: null,
    error: null,
};

function reducer(state: AuthState, action: Action): AuthState {
    switch (action.type) {
        case 'restore_complete':
            return {
                ...state,
                status: action.payload.status,
                serverUrl: action.payload.serverUrl,
                identity: action.payload.identity,
                error: null,
            };
        case 'login_success':
            return { ...state, status: 'authenticated', identity: action.identity, error: null };
        case 'login_failure':
            return { ...state, status: 'unauthenticated', error: action.error };
        case 'identity_updated':
            return { ...state, identity: action.identity };
        case 'logout':
        case 'auth_failed':
            return {
                ...state,
                status: 'unauthenticated',
                identity: null,
                error: action.type === 'auth_failed' ? state.error : null,
            };
        case 'server_url_set':
            return { ...state, serverUrl: action.url, status: 'unauthenticated', identity: null };
        case 'clear_error':
            return { ...state, error: null };
        default:
            return state;
    }
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }): React.JSX.Element {
    const [state, dispatch] = useReducer(reducer, initial);
    const accessTokenRef = useRef<{ current: string | null }>({ current: null });
    const refreshTokenRef = useRef<string | null>(null);
    const apiRef = useRef<MobileApiBundle | null>(null);
    const serverUrlRef = useRef<string | null>(null);

    const persistTokens = useCallback(async (tokens: MobileAuthResponse) => {
        accessTokenRef.current.current = tokens.access_token;
        refreshTokenRef.current = tokens.refresh_token;
        await tokenStorage.persistTokens(tokens);
    }, []);

    const handleAuthFailed = useCallback(async () => {
        accessTokenRef.current.current = null;
        refreshTokenRef.current = null;
        await tokenStorage.clear();
        dispatch({ type: 'auth_failed' });
    }, []);

    const buildApi = useCallback(
        (serverUrl: string): MobileApiBundle => {
            return buildMobileApi({
                serverUrl,
                accessToken: accessTokenRef.current,
                getRefreshToken: () => refreshTokenRef.current,
                onTokensRefreshed: persistTokens,
                onAuthFailed: handleAuthFailed,
            });
        },
        [persistTokens, handleAuthFailed],
    );

    // Cold-start restore.
    useEffect(() => {
        let cancelled = false;
        (async () => {
            const serverUrl = await serverUrlStorage.get();
            serverUrlRef.current = serverUrl;
            if (!serverUrl) {
                if (!cancelled) {
                    dispatch({
                        type: 'restore_complete',
                        payload: { serverUrl: null, identity: null, status: 'needs_server' },
                    });
                }
                return;
            }
            const [refresh, access, identity] = await Promise.all([
                tokenStorage.getRefreshToken(),
                tokenStorage.getAccessToken(),
                tokenStorage.getIdentity(),
            ]);
            refreshTokenRef.current = refresh;
            accessTokenRef.current.current = access;

            if (!refresh) {
                if (!cancelled) {
                    dispatch({
                        type: 'restore_complete',
                        payload: { serverUrl, identity: null, status: 'unauthenticated' },
                    });
                }
                return;
            }

            apiRef.current = buildApi(serverUrl);

            try {
                const me = await apiRef.current.authApi.getCurrentUser();
                if (cancelled) return;
                await tokenStorage.setIdentity(me);
                dispatch({
                    type: 'restore_complete',
                    payload: { serverUrl, identity: me, status: 'authenticated' },
                });
                return;
            } catch {
                // /me failed — try a manual refresh and re-fetch /me. The shared
                // client's interceptor would normally handle this on the FIRST
                // call to a protected endpoint, but it only refreshes when an
                // access token was actually attached. On cold start we may have
                // no access token at all (in-memory cache is empty), so do an
                // explicit refresh here.
            }
            try {
                const tokens = await apiRef.current.authApi.refresh(refresh);
                if (!tokens || cancelled) {
                    throw new Error('Empty refresh response');
                }
                await persistTokens(tokens);
                const me = await apiRef.current.authApi.getCurrentUser();
                if (cancelled) return;
                await tokenStorage.setIdentity(me);
                dispatch({
                    type: 'restore_complete',
                    payload: { serverUrl, identity: me, status: 'authenticated' },
                });
            } catch {
                if (cancelled) return;
                accessTokenRef.current.current = null;
                refreshTokenRef.current = null;
                await tokenStorage.clear();
                dispatch({
                    type: 'restore_complete',
                    payload: { serverUrl, identity: identity ?? null, status: 'unauthenticated' },
                });
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [buildApi, persistTokens]);

    const ensureApi = useCallback((): MobileApiBundle => {
        if (apiRef.current) {
            return apiRef.current;
        }
        const serverUrl = serverUrlRef.current ?? state.serverUrl;
        if (!serverUrl) {
            throw new Error('No server URL configured');
        }
        apiRef.current = buildApi(serverUrl);
        return apiRef.current;
    }, [buildApi, state.serverUrl]);

    const login = useCallback(
        async (email: string, password: string) => {
            try {
                const api = ensureApi();
                const outcome = await api.authApi.login({ email, password });
                if (outcome.type === 'mfa_required') {
                    dispatch({
                        type: 'login_failure',
                        error:
                            'MFA is enabled on this account but mobile MFA is not yet supported in this build. Disable MFA on the web client to sign in here.',
                    });
                    return;
                }
                await persistTokens(outcome.tokens);
                const identity: MeResponse = {
                    user_id: outcome.tokens.user_id,
                    tenant_id: outcome.tokens.tenant_id,
                    email: outcome.tokens.email,
                    role: outcome.tokens.role,
                };
                dispatch({ type: 'login_success', identity });
            } catch (err) {
                dispatch({
                    type: 'login_failure',
                    error: extractErrorMessage(err),
                });
            }
        },
        [ensureApi, persistTokens],
    );

    const logout = useCallback(async () => {
        const api = apiRef.current;
        if (api) {
            try {
                await api.authApi.logout();
            } catch {
                // Server-side logout is best-effort: a network failure here
                // shouldn't keep the user "stuck" logged in client-side.
            }
        }
        accessTokenRef.current.current = null;
        refreshTokenRef.current = null;
        await tokenStorage.clear();
        dispatch({ type: 'logout' });
    }, []);

    const refreshMe = useCallback(async () => {
        const api = apiRef.current;
        if (!api) return;
        try {
            const me = await api.authApi.getCurrentUser();
            await tokenStorage.setIdentity(me);
            dispatch({ type: 'identity_updated', identity: me });
        } catch {
            // Interceptor will have already kicked off a refresh attempt and
            // possibly an onAuthFailed → 'auth_failed' dispatch. Nothing to do.
        }
    }, []);

    const setServerUrl = useCallback(
        async (url: string) => {
            await serverUrlStorage.set(url);
            // Changing the server invalidates any cached session.
            accessTokenRef.current.current = null;
            refreshTokenRef.current = null;
            await tokenStorage.clear();
            serverUrlRef.current = url;
            apiRef.current = buildApi(url);
            dispatch({ type: 'server_url_set', url });
        },
        [buildApi],
    );

    const clearError = useCallback(() => {
        dispatch({ type: 'clear_error' });
    }, []);

    const getDataApis = useCallback((): DataApis | null => {
        const api = apiRef.current;
        if (!api) return null;
        return buildDataApis(api.client);
    }, []);

    const value = useMemo<AuthContextValue>(
        () => ({
            ...state,
            login,
            logout,
            refreshMe,
            setServerUrl,
            clearError,
            getDataApis,
        }),
        [state, login, logout, refreshMe, setServerUrl, clearError, getDataApis],
    );

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
    const ctx = useContext(AuthContext);
    if (!ctx) {
        throw new Error('useAuth() must be used inside <AuthProvider>');
    }
    return ctx;
}
