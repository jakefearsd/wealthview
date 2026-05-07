import { render, screen, act, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import { getCurrentUser, logout as logoutApi } from '../api/auth';
import type { AuthResponse, CurrentUserResponse } from '../types/auth';

vi.mock('../api/auth', () => ({
    getCurrentUser: vi.fn(),
    logout: vi.fn(),
}));

const mockGetCurrentUser = vi.mocked(getCurrentUser);
const mockLogoutApi = vi.mocked(logoutApi);

const SAMPLE_USER: CurrentUserResponse = {
    user_id: 'user-1',
    tenant_id: 'tenant-1',
    email: 'alice@test.com',
    role: 'admin',
};

const SAMPLE_AUTH_RESPONSE: AuthResponse = {
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
        mockLogoutApi.mockResolvedValue();
    });

    it('initializes unauthenticated when /auth/me returns 401', async () => {
        mockGetCurrentUser.mockRejectedValue(new Error('401'));

        render(
            <AuthProvider>
                <Consumer onRender={() => {}} />
            </AuthProvider>,
        );

        await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('ready'));
        expect(screen.getByTestId('authed').textContent).toBe('no');
        expect(screen.getByTestId('email').textContent).toBe('');
        expect(mockGetCurrentUser).toHaveBeenCalledTimes(1);
    });

    it('bootstraps from cookies by fetching the current user on mount', async () => {
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

    it('loginSuccess sets authenticated state without touching storage', async () => {
        mockGetCurrentUser.mockRejectedValue(new Error('401'));
        let latestAuth!: ReturnType<typeof useAuth>;

        render(
            <AuthProvider>
                <Consumer onRender={(a) => { latestAuth = a; }} />
            </AuthProvider>,
        );
        await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('ready'));

        act(() => { latestAuth.loginSuccess(SAMPLE_AUTH_RESPONSE); });

        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));
        expect(screen.getByTestId('email').textContent).toBe('bob@test.com');
        expect(screen.getByTestId('role').textContent).toBe('member');
        expect(screen.getByTestId('tenant').textContent).toBe('tenant-2');
    });

    it('logout calls the server to clear cookies and resets auth state', async () => {
        mockGetCurrentUser.mockResolvedValue(SAMPLE_USER);
        let latestAuth!: ReturnType<typeof useAuth>;

        render(
            <AuthProvider>
                <Consumer onRender={(a) => { latestAuth = a; }} />
            </AuthProvider>,
        );
        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));

        await act(async () => { await latestAuth.logout(); });

        expect(mockLogoutApi).toHaveBeenCalledTimes(1);
        expect(screen.getByTestId('authed').textContent).toBe('no');
        expect(screen.getByTestId('email').textContent).toBe('');
        expect(screen.getByTestId('role').textContent).toBe('');
    });

    it('logout still clears local auth state if the server call fails', async () => {
        mockGetCurrentUser.mockResolvedValue(SAMPLE_USER);
        mockLogoutApi.mockRejectedValueOnce(new Error('network down'));
        let latestAuth!: ReturnType<typeof useAuth>;

        render(
            <AuthProvider>
                <Consumer onRender={(a) => { latestAuth = a; }} />
            </AuthProvider>,
        );
        await waitFor(() => expect(screen.getByTestId('authed').textContent).toBe('yes'));

        await act(async () => { await latestAuth.logout(); });

        expect(screen.getByTestId('authed').textContent).toBe('no');
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
