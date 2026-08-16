import { vi } from 'vitest';
// Type-only: erased at compile time, so this does not pull the (usually mocked)
// AuthContext module into the runtime graph of a test that mocks it.
import type { useAuth } from '../context/AuthContext';

type AuthValue = ReturnType<typeof useAuth>;

/**
 * Builds an authenticated `useAuth()` value for a given role, so a test can flip the role
 * without restating the whole context shape:
 *
 *     vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));
 *     vi.mocked(useAuth).mockReturnValue(authAs('super_admin'));
 *
 * Role strings are the ones the server actually emits (TokenService: `super_admin` for a
 * super admin, otherwise the stored `role`), so tests exercise real values.
 */
export function authAs(role: string | null): AuthValue {
    return {
        isAuthenticated: true,
        userId: 'u1',
        tenantId: 't1',
        email: 'demo@wealthview.local',
        role,
        loading: false,
        loginSuccess: vi.fn(),
        logout: vi.fn(),
    } as unknown as AuthValue;
}
