import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../context/AuthContext', () => ({
    useAuth: vi.fn(),
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
import { useAuth } from '../../context/AuthContext';
import { getAllUsers, resetPassword, setUserActive } from '../../api/adminUsers';
import { listUsers } from '../../api/tenant';
import UsersSection from './UsersSection';
import { authAs } from '../../testutil/auth';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockUseAuth = vi.mocked(useAuth);

// authAs() puts the caller in tenant 't1'.
const adminUsers = [
    {
        id: 'u-1',
        email: 'jake@example.com',
        role: 'admin',
        tenant_id: 't1',
        tenant_name: 'Demo',
        is_active: true,
        created_at: '2026-01-01T00:00:00Z',
    },
];

const otherTenantUser = {
    id: 'u-2',
    email: 'pat@other.example',
    role: 'member',
    tenant_id: 't2',
    tenant_name: 'Other',
    is_active: true,
    created_at: '2026-02-01T00:00:00Z',
};

const superAdminUser = {
    id: 'u-3',
    email: 'root@example.com',
    role: 'super_admin',
    tenant_id: 't1',
    tenant_name: 'Demo',
    is_active: true,
    created_at: '2026-01-01T00:00:00Z',
};

interface QueryState {
    admin?: unknown[] | null;
    tenant?: unknown[] | null;
    adminError?: string | null;
    tenantError?: string | null;
    /** Run the fetchers the component passes, to see which endpoints it would hit. */
    runFetchers?: boolean;
}

function setupMocks({ admin = adminUsers, tenant = [], adminError = null, tenantError = null, runFetchers = false }: QueryState = {}) {
    let call = 0;
    mockUseApiQuery.mockImplementation((fetchFn) => {
        const index = call++ % 2;
        if (runFetchers) void fetchFn();
        return index === 0
            ? { data: admin, loading: false, error: adminError, refetch: vi.fn() }
            : { data: tenant, loading: false, error: tenantError, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

describe('UsersSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(getAllUsers).mockResolvedValue([]);
        vi.mocked(listUsers).mockResolvedValue([]);
        mockUseAuth.mockReturnValue(authAs('super_admin'));
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

    // === roles the server accepts (S6) ===

    it('offers only the roles the server accepts, with no Super Admin option', () => {
        setupMocks();
        render(<UsersSection />);

        const select = screen.getByRole('combobox', { name: 'Role for jake@example.com' });
        const options = within(select).getAllByRole('option').map((o) => o.textContent);
        expect(options).toEqual(['Admin', 'Member', 'Viewer']);
    });

    it('shows a super admin row as a read-only role label', () => {
        setupMocks({ admin: [superAdminUser] });
        render(<UsersSection />);

        expect(screen.getByText('Super Admin')).toBeInTheDocument();
        expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    });

    // === tenant-scoped endpoints (S7) ===

    it('hides the role select and Delete for a user outside the super admin\'s own tenant', () => {
        setupMocks({ admin: [otherTenantUser] });
        render(<UsersSection />);

        const row = screen.getByText('pat@other.example').closest('tr') as HTMLElement;
        expect(within(row).queryByRole('combobox')).not.toBeInTheDocument();
        expect(within(row).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
        expect(within(row).getByText('Member')).toBeInTheDocument();
    });

    it('keeps reset-password and activation for a user in another tenant', () => {
        setupMocks({ admin: [otherTenantUser] });
        render(<UsersSection />);

        expect(screen.getByRole('button', { name: 'Reset PW' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument();
    });

    it('keeps the role select and Delete for a user in the super admin\'s own tenant', () => {
        setupMocks();
        render(<UsersSection />);

        expect(screen.getByRole('combobox', { name: 'Role for jake@example.com' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    });

    // === only the matching query fires, errors are shown (S12) ===

    it('fetches only the all-users list for a super admin', () => {
        setupMocks({ runFetchers: true });
        render(<UsersSection />);

        expect(getAllUsers).toHaveBeenCalled();
        expect(listUsers).not.toHaveBeenCalled();
    });

    it('fetches only the tenant users list for a tenant admin', () => {
        mockUseAuth.mockReturnValue(authAs('admin'));
        setupMocks({ runFetchers: true });
        render(<UsersSection />);

        expect(listUsers).toHaveBeenCalled();
        expect(getAllUsers).not.toHaveBeenCalled();
    });

    it('shows the error rather than "No users found" when the users query fails', () => {
        setupMocks({ admin: null, adminError: 'Request failed' });
        render(<UsersSection />);

        expect(screen.getByText('Request failed')).toBeInTheDocument();
        expect(screen.queryByText('No users found')).not.toBeInTheDocument();
    });

    it('shows the tenant query error to a tenant admin, ignoring the all-users query', () => {
        mockUseAuth.mockReturnValue(authAs('admin'));
        setupMocks({ tenant: null, tenantError: 'Tenant list failed', adminError: 'Forbidden' });
        render(<UsersSection />);

        expect(screen.getByText('Tenant list failed')).toBeInTheDocument();
        expect(screen.queryByText('Forbidden')).not.toBeInTheDocument();
    });

    // === reset-password dialog (S11, S17) ===

    it('presents the reset form as a modal dialog with a password length hint', () => {
        setupMocks();
        render(<UsersSection />);

        fireEvent.click(screen.getByText('Reset PW'));

        const dialog = screen.getByRole('dialog', { name: 'Reset Password' });
        expect(dialog).toHaveAttribute('aria-modal', 'true');
        expect(within(dialog).getByText(/12.64 characters/)).toBeInTheDocument();
        expect(within(dialog).getByLabelText('New password')).toHaveFocus();
    });

    it('sends the password exactly as typed, without trimming', async () => {
        setupMocks();
        vi.mocked(resetPassword).mockResolvedValue(undefined);
        render(<UsersSection />);

        fireEvent.click(screen.getByText('Reset PW'));
        fireEvent.change(screen.getByLabelText('New password'), { target: { value: '  spaced pass phrase  ' } });
        fireEvent.click(screen.getByRole('button', { name: 'Reset Password' }));

        await waitFor(() => expect(resetPassword).toHaveBeenCalledWith('u-1', '  spaced pass phrase  '));
    });

    it('closes the reset dialog on Escape', () => {
        setupMocks();
        render(<UsersSection />);
        fireEvent.click(screen.getByText('Reset PW'));

        fireEvent.keyDown(document, { key: 'Escape' });

        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
});
