import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router';
import ProtectedRoute from './ProtectedRoute';

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

import { useAuth } from '../context/AuthContext';

const mockUseAuth = vi.mocked(useAuth);

type AuthValue = ReturnType<typeof useAuth>;

function authValue(opts: { isAuthenticated: boolean; loading: boolean }): AuthValue {
    return {
        isAuthenticated: opts.isAuthenticated,
        userId: null,
        tenantId: null,
        email: null,
        role: null,
        loading: opts.loading,
        loginSuccess: vi.fn(),
        logout: vi.fn().mockResolvedValue(undefined),
    } as unknown as AuthValue;
}

function renderAt(entry: string) {
    return render(
        <MemoryRouter initialEntries={[entry]}>
            <Routes>
                <Route
                    path="/secret"
                    element={
                        <ProtectedRoute>
                            <div data-testid="secret">protected content</div>
                        </ProtectedRoute>
                    }
                />
                <Route path="/login" element={<div data-testid="login">login page</div>} />
            </Routes>
        </MemoryRouter>,
    );
}

describe('ProtectedRoute', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the loading placeholder while auth is bootstrapping', () => {
        mockUseAuth.mockReturnValue(authValue({ isAuthenticated: false, loading: true }));

        renderAt('/secret');

        expect(screen.getByText('Loading...')).toBeInTheDocument();
        expect(screen.queryByTestId('secret')).toBeNull();
    });

    it('redirects to /login when the user is unauthenticated', () => {
        mockUseAuth.mockReturnValue(authValue({ isAuthenticated: false, loading: false }));

        renderAt('/secret');

        expect(screen.getByTestId('login')).toBeInTheDocument();
        expect(screen.queryByTestId('secret')).toBeNull();
    });

    it('renders children when authenticated', () => {
        mockUseAuth.mockReturnValue(authValue({ isAuthenticated: true, loading: false }));

        renderAt('/secret');

        expect(screen.getByTestId('secret')).toBeInTheDocument();
        expect(screen.queryByTestId('login')).toBeNull();
    });
});
