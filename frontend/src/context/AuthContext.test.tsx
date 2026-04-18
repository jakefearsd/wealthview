import { render, screen, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import { getAccessToken, setTokens, clearTokens } from '../utils/storage';
import { getCurrentUser } from '../api/auth';
import type { AuthResponse, CurrentUserResponse } from '../types/auth';

vi.mock('../utils/storage', () => ({
    getAccessToken: vi.fn(),
    setTokens: vi.fn(),
    clearTokens: vi.fn(),
}));

vi.mock('../api/auth', () => ({
    getCurrentUser: vi.fn(),
}));

const mockGetAccessToken = vi.mocked(getAccessToken);
const mockSetTokens = vi.mocked(setTokens);
const mockClearTokens = vi.mocked(clearTokens);
const mockGetCurrentUser = vi.mocked(getCurrentUser);

const SAMPLE_USER: CurrentUserResponse = {
    user_id: 'user-1',
    tenant_id: 'tenant-1',
    email: 'alice@test.com',
    role: 'admin',
};

const SAMPLE_AUTH_RESPONSE: AuthResponse = {
    access_token: 'access-123',
    refresh_token: 'refresh-456',
    user_id: 'user-2',
    tenant_id: 'tenant-2',
    email: 'bob@test.com',
    role: 'member',
};

function Consumer({ onRender }: { onRender: (auth: ReturnType<typeof useAuth>) => void }) {
    const auth = useAuth();
    onRender(auth);
    return (
        <div>
            <span data-testid="authed">{auth.isAuthenticated ? 'yes' : 'no'}</span>
            <span data-testid="loading">{auth.loading ? 'loading' : 'ready'}</span>
            <span data-testid="email">{auth.email ?? ''}</span>
            <span data-testid="role">{auth.role ?? ''}</span>
            <span data-testid="tenant">{auth.tenantId ?? ''}</span>
        </div>
    );
}

describe('AuthProvider', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('initializes unauthenticated when no token is stored', async () => {
        mockGetAccessToken.mockReturnValue(null);

        render(
            <AuthProvider>
                <Consumer onRender={() => {}} />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('ready'));
        expect(screen.getByTestId('authed').textContent).toBe('no');
        expect(screen.getByTestId('email').textContent).toBe('');
        expect(mockGetCurrentUser).not.toHaveBeenCalled();
    });

    it('bootstraps from stored token by fetching the current user', async () => {
        mockGetAccessToken.mockReturnValue('existing-token');
        mockGetCurrentUser.mockResolvedValue(SAMPLE_USER);

        render(
            <AuthProvider>
                <Consumer onRender={() => {}} />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));
        expect(screen.getByTestId('email').textContent).toBe('alice@test.com');
        expect(screen.getByTestId('role').textContent).toBe('admin');
        expect(screen.getByTestId('tenant').textContent).toBe('tenant-1');
    });

    it('clears tokens and stays unauthenticated when bootstrap fetch fails', async () => {
        mockGetAccessToken.mockReturnValue('stale-token');
        mockGetCurrentUser.mockRejectedValue(new Error('401'));

        render(
            <AuthProvider>
                <Consumer onRender={() => {}} />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('ready'));
        expect(screen.getByTestId('authed').textContent).toBe('no');
        expect(mockClearTokens).toHaveBeenCalledTimes(1);
    });

    it('loginSuccess persists tokens and sets authenticated state', async () => {
        mockGetAccessToken.mockReturnValue(null);
        let latestAuth!: ReturnType<typeof useAuth>;

        render(
            <AuthProvider>
                <Consumer onRender={(a) => { latestAuth = a; }} />
            </AuthProvider>,
        );
        await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('ready'));

        act(() => { latestAuth.loginSuccess(SAMPLE_AUTH_RESPONSE); });

        expect(mockSetTokens).toHaveBeenCalledWith('access-123', 'refresh-456');
        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));
        expect(screen.getByTestId('email').textContent).toBe('bob@test.com');
        expect(screen.getByTestId('role').textContent).toBe('member');
        expect(screen.getByTestId('tenant').textContent).toBe('tenant-2');
    });

    it('logout clears tokens and resets auth state', async () => {
        mockGetAccessToken.mockReturnValue('existing-token');
        mockGetCurrentUser.mockResolvedValue(SAMPLE_USER);
        let latestAuth!: ReturnType<typeof useAuth>;

        render(
            <AuthProvider>
                <Consumer onRender={(a) => { latestAuth = a; }} />
            </AuthProvider>,
        );
        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));

        act(() => { latestAuth.logout(); });

        expect(mockClearTokens).toHaveBeenCalledTimes(1);
        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('no'));
        expect(screen.getByTestId('email').textContent).toBe('');
        expect(screen.getByTestId('role').textContent).toBe('');
    });
});

describe('useAuth', () => {
    it('throws when used outside AuthProvider', () => {
        const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
        try {
            expect(() => render(<Consumer onRender={() => {}} />)).toThrow(
                /useAuth must be used within AuthProvider/,
            );
        } finally {
            spy.mockRestore();
        }
    });
});
