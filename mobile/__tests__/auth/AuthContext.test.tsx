/**
 * @format
 */

import React from 'react';
import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';

jest.mock('../../src/auth/tokenStorage', () => ({
    __esModule: true,
    tokenStorage: {
        getRefreshToken: jest.fn(),
        setRefreshToken: jest.fn(),
        getAccessToken: jest.fn(),
        setAccessToken: jest.fn(),
        getIdentity: jest.fn(),
        setIdentity: jest.fn(),
        persistTokens: jest.fn(),
        clear: jest.fn(),
    },
}));

jest.mock('../../src/config/serverUrlStorage', () => ({
    __esModule: true,
    serverUrlStorage: {
        get: jest.fn(),
        set: jest.fn(),
        clear: jest.fn(),
    },
    isValidServerUrl: (v: string) => /^https?:\/\/[^/]+/.test(String(v ?? '')),
    normalizeServerUrl: (v: string) => String(v ?? '').replace(/\/+$/, ''),
    apiBaseUrl: (v: string) => `${String(v ?? '').replace(/\/+$/, '')}/api/v1`,
}));

jest.mock('../../src/auth/apiClient', () => ({
    __esModule: true,
    buildMobileApi: jest.fn(),
}));

import { AuthProvider, useAuth } from '../../src/auth/AuthContext';
import { tokenStorage } from '../../src/auth/tokenStorage';
import { serverUrlStorage } from '../../src/config/serverUrlStorage';
import { buildMobileApi } from '../../src/auth/apiClient';

const mockTokenStorage = tokenStorage as jest.Mocked<typeof tokenStorage>;
const mockServerUrlStorage = serverUrlStorage as jest.Mocked<typeof serverUrlStorage>;
const mockBuildMobileApi = buildMobileApi as jest.MockedFunction<typeof buildMobileApi>;

const mockAuthApi = {
    login: jest.fn(),
    register: jest.fn(),
    refresh: jest.fn(),
    logout: jest.fn(),
    getCurrentUser: jest.fn(),
};

const SAMPLE_TOKENS = {
    access_token: 'at-1',
    refresh_token: 'rt-1',
    user_id: 'u1',
    tenant_id: 't1',
    email: 'demo@x',
    role: 'member',
};

function Probe() {
    const { status, identity, error, login, logout } = useAuth();
    return (
        <>
            <Text testID="status">{status}</Text>
            <Text testID="email">{identity?.email ?? 'none'}</Text>
            <Text testID="error">{error ?? 'none'}</Text>
            <Text
                testID="login-trigger"
                onPress={() => {
                    void login('demo@x', 'pw');
                }}>
                login
            </Text>
            <Text
                testID="logout-trigger"
                onPress={() => {
                    void logout();
                }}>
                logout
            </Text>
        </>
    );
}

function renderProbe(serverUrl: string | null = 'https://api.example.com') {
    mockServerUrlStorage.get.mockResolvedValue(serverUrl);
    return render(
        <AuthProvider>
            <Probe />
        </AuthProvider>,
    );
}

beforeEach(() => {
    jest.clearAllMocks();
    mockTokenStorage.getRefreshToken.mockResolvedValue(null);
    mockTokenStorage.getAccessToken.mockResolvedValue(null);
    mockTokenStorage.getIdentity.mockResolvedValue(null);
    mockTokenStorage.setIdentity.mockResolvedValue(undefined);
    mockTokenStorage.persistTokens.mockResolvedValue(undefined);
    mockTokenStorage.clear.mockResolvedValue(undefined);
    mockServerUrlStorage.get.mockResolvedValue(null);
    mockServerUrlStorage.clear.mockResolvedValue(undefined);
    mockBuildMobileApi.mockReturnValue({
        client: {} as never,
        authApi: mockAuthApi as never,
    });
});

describe('AuthContext', () => {
    it('starts in restoring state and falls through to needs_server when no URL is configured', async () => {
        const { getByTestId } = renderProbe(null);

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('needs_server');
        });
    });

    it('falls through to unauthenticated when server is configured but no refresh token exists', async () => {
        const { getByTestId } = renderProbe('https://api.example.com');

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });
    });

    it('restores from keychain on mount: identity present + /me succeeds → authenticated', async () => {
        mockTokenStorage.getRefreshToken.mockResolvedValue('rt-cached');
        mockTokenStorage.getAccessToken.mockResolvedValue('at-cached');
        mockTokenStorage.getIdentity.mockResolvedValue({
            user_id: 'u1',
            tenant_id: 't1',
            email: 'cached@x',
            role: 'member',
        });
        mockAuthApi.getCurrentUser.mockResolvedValue({
            user_id: 'u1',
            tenant_id: 't1',
            email: 'cached@x',
            role: 'member',
        });

        const { getByTestId } = renderProbe('https://api.example.com');

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('authenticated');
        });
        expect(getByTestId('email').props.children).toBe('cached@x');
        expect(mockAuthApi.getCurrentUser).toHaveBeenCalled();
    });

    it('on cold start with /me failing, attempts refresh and re-tries /me', async () => {
        mockTokenStorage.getRefreshToken.mockResolvedValue('rt-cached');
        mockTokenStorage.getIdentity.mockResolvedValue({
            user_id: 'u1',
            tenant_id: 't1',
            email: 'cached@x',
            role: 'member',
        });
        let getMeCalls = 0;
        mockAuthApi.getCurrentUser.mockImplementation(() => {
            getMeCalls++;
            if (getMeCalls === 1) {
                return Promise.reject({ response: { status: 401 } });
            }
            return Promise.resolve({
                user_id: 'u1',
                tenant_id: 't1',
                email: 'cached@x',
                role: 'member',
            });
        });
        mockAuthApi.refresh.mockResolvedValue(SAMPLE_TOKENS);

        const { getByTestId } = renderProbe('https://api.example.com');

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('authenticated');
        });
        expect(mockAuthApi.refresh).toHaveBeenCalledWith('rt-cached');
        expect(mockTokenStorage.persistTokens).toHaveBeenCalledWith(SAMPLE_TOKENS);
    });

    it('on cold start with both /me and refresh failing, ends up unauthenticated and clears tokens', async () => {
        mockTokenStorage.getRefreshToken.mockResolvedValue('rt-bad');
        mockAuthApi.getCurrentUser.mockRejectedValue({ response: { status: 401 } });
        mockAuthApi.refresh.mockRejectedValue(new Error('refresh down'));

        const { getByTestId } = renderProbe('https://api.example.com');

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });
        expect(mockTokenStorage.clear).toHaveBeenCalled();
    });

    it('login() persists tokens, populates identity, and transitions to authenticated', async () => {
        mockAuthApi.login.mockResolvedValue({ type: 'tokens', tokens: SAMPLE_TOKENS });

        const { getByTestId } = renderProbe('https://api.example.com');
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });

        await act(async () => {
            getByTestId('login-trigger').props.onPress();
        });

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('authenticated');
        });
        expect(mockTokenStorage.persistTokens).toHaveBeenCalledWith(SAMPLE_TOKENS);
        expect(getByTestId('email').props.children).toBe('demo@x');
    });

    it('login() with bad credentials surfaces an error and stays unauthenticated', async () => {
        mockAuthApi.login.mockRejectedValue({
            response: {
                status: 401,
                data: { error: 'UNAUTHORIZED', message: 'Invalid email or password', status: 401 },
            },
        });

        const { getByTestId } = renderProbe('https://api.example.com');
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });

        await act(async () => {
            getByTestId('login-trigger').props.onPress();
        });

        await waitFor(() => {
            expect(getByTestId('error').props.children).toBe('Invalid email or password');
        });
        expect(getByTestId('status').props.children).toBe('unauthenticated');
    });

    it('login() with mfa_required surfaces a friendly "not supported" error', async () => {
        mockAuthApi.login.mockResolvedValue({ type: 'mfa_required', mfa_token: 'mfa-jwt' });

        const { getByTestId } = renderProbe('https://api.example.com');
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });

        await act(async () => {
            getByTestId('login-trigger').props.onPress();
        });

        await waitFor(() => {
            expect(getByTestId('error').props.children).toContain('MFA');
        });
        expect(getByTestId('status').props.children).toBe('unauthenticated');
    });

    it('logout() calls the server, clears tokens, and returns to unauthenticated', async () => {
        mockAuthApi.login.mockResolvedValue({ type: 'tokens', tokens: SAMPLE_TOKENS });
        mockAuthApi.logout.mockResolvedValue(undefined);

        const { getByTestId } = renderProbe('https://api.example.com');
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });

        await act(async () => {
            getByTestId('login-trigger').props.onPress();
        });
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('authenticated');
        });

        await act(async () => {
            getByTestId('logout-trigger').props.onPress();
        });

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });
        expect(mockAuthApi.logout).toHaveBeenCalled();
        expect(mockTokenStorage.clear).toHaveBeenCalled();
    });

    it('logout() still clears local state even if the server logout call fails', async () => {
        mockAuthApi.login.mockResolvedValue({ type: 'tokens', tokens: SAMPLE_TOKENS });
        mockAuthApi.logout.mockRejectedValue(new Error('network'));

        const { getByTestId } = renderProbe('https://api.example.com');
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });

        await act(async () => {
            getByTestId('login-trigger').props.onPress();
        });
        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('authenticated');
        });

        await act(async () => {
            getByTestId('logout-trigger').props.onPress();
        });

        await waitFor(() => {
            expect(getByTestId('status').props.children).toBe('unauthenticated');
        });
        expect(mockTokenStorage.clear).toHaveBeenCalled();
    });
});
