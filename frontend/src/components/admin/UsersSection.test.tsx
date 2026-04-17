import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../context/AuthContext', () => ({
    useAuth: () => ({ role: 'super_admin' }),
}));

vi.mock('../../api/adminUsers', () => ({
    getAllUsers: vi.fn(),
    resetPassword: vi.fn(),
    setUserActive: vi.fn(),
}));

vi.mock('../../api/tenant', () => ({
    listUsers: vi.fn(),
    updateUserRole: vi.fn(),
    deleteUser: vi.fn(),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../../hooks/useApiQuery';
import { resetPassword, setUserActive } from '../../api/adminUsers';
import UsersSection from './UsersSection';

const mockUseApiQuery = vi.mocked(useApiQuery);

const adminUsers = [
    {
        id: 'u-1',
        email: 'jake@example.com',
        role: 'admin',
        tenant_name: 'Demo',
        is_active: true,
        created_at: '2026-01-01T00:00:00Z',
    },
];

function setupMocks() {
    let call = 0;
    mockUseApiQuery.mockImplementation(() => {
        call++;
        if (call === 1) {
            return { data: adminUsers, loading: false, error: null, refetch: vi.fn() };
        }
        return { data: [], loading: false, error: null, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

describe('UsersSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders admin user rows', () => {
        setupMocks();
        render(<UsersSection />);
        expect(screen.getByText('jake@example.com')).toBeInTheDocument();
    });

    it('triggers a password reset via modal', async () => {
        setupMocks();
        vi.mocked(resetPassword).mockResolvedValue(undefined);
        render(<UsersSection />);

        fireEvent.click(screen.getByText('Reset PW'));
        const passwordInput = screen.getByPlaceholderText('New password');
        fireEvent.change(passwordInput, { target: { value: 'new-secret' } });
        fireEvent.click(screen.getByRole('button', { name: 'Reset Password' }));

        await waitFor(() => {
            expect(resetPassword).toHaveBeenCalledWith('u-1', 'new-secret');
        });
    });

    it('toggles user active state', async () => {
        setupMocks();
        vi.mocked(setUserActive).mockResolvedValue(undefined);
        render(<UsersSection />);

        fireEvent.click(screen.getByText('Deactivate'));
        await waitFor(() => {
            expect(setUserActive).toHaveBeenCalledWith('u-1', false);
        });
    });
});
