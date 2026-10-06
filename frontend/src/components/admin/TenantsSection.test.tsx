import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { TenantDetail } from '../../types/admin';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../api/admin', () => ({
    listTenantDetails: vi.fn(),
    createTenant: vi.fn(),
    setTenantActive: vi.fn(),
    generateTenantInviteCode: vi.fn(),
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
import toast from 'react-hot-toast';
import { createTenant, setTenantActive, generateTenantInviteCode } from '../../api/admin';
import TenantsSection from './TenantsSection';
import type { InviteCode } from '../../types/tenant';

const mockUseApiQuery = vi.mocked(useApiQuery);

const inviteFor = (code: string): InviteCode => ({
    id: 'inv-1',
    code,
    expires_at: '2026-03-15T12:00:00Z',
    consumed: false,
    is_revoked: false,
    used_by_email: null,
    created_by_email: 'root@example.com',
    created_at: '2026-03-08T00:00:00Z',
});

const acmeTenant: TenantDetail = {
    id: 't-1',
    name: 'Acme Corp',
    is_active: true,
    user_count: 3,
    account_count: 5,
    created_at: '2026-01-01T00:00:00Z',
};

describe('TenantsSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders tenants from the hook', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [acmeTenant], loading: false, error: null, refetch: vi.fn() } as any);
        render(<TenantsSection />);
        expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    it('creates a new tenant from the input', async () => {
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch } as any);
        vi.mocked(createTenant).mockResolvedValue(acmeTenant);
        render(<TenantsSection />);

        const input = screen.getByRole('textbox');
        fireEvent.change(input, { target: { value: 'New Corp' } });
        fireEvent.click(screen.getByText('Create'));

        await waitFor(() => {
            expect(createTenant).toHaveBeenCalledWith('New Corp');
        });
    });

    it('toggles tenant active flag', async () => {
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [acmeTenant], loading: false, error: null, refetch } as any);
        vi.mocked(setTenantActive).mockResolvedValue(undefined);
        render(<TenantsSection />);

        fireEvent.click(screen.getByText(/Disable/i));
        await waitFor(() => {
            expect(setTenantActive).toHaveBeenCalledWith('t-1', false);
        });
    });

    it('shows an error with retry rather than an empty table when tenants fail to load', () => {
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: 'Tenants unavailable', refetch } as any);
        render(<TenantsSection />);

        expect(screen.getByText('Tenants unavailable')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
        expect(refetch).toHaveBeenCalled();
    });

    it('shows the created date as an ISO date', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [{ ...acmeTenant, created_at: '2026-01-01T12:00:00Z' }], loading: false, error: null, refetch: vi.fn() } as any);
        render(<TenantsSection />);

        expect(screen.getByText('2026-01-01')).toBeInTheDocument();
    });
    describe('invite codes', () => {
        function renderWithTenants(tenants: TenantDetail[]) {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            mockUseApiQuery.mockReturnValue({ data: tenants, loading: false, error: null, refetch: vi.fn() } as any);
            render(<TenantsSection />);
        }

        it('creates an invite code for the chosen tenant and shows it with its expiry', async () => {
            vi.mocked(generateTenantInviteCode).mockResolvedValue(inviteFor('NEWCODE123'));
            renderWithTenants([acmeTenant]);

            fireEvent.click(screen.getByRole('button', { name: 'Create invite code for Acme Corp' }));

            expect(await screen.findByText('NEWCODE123')).toBeInTheDocument();
            expect(generateTenantInviteCode).toHaveBeenCalledWith('t-1');
            expect(screen.getByText(/2026-03-15/)).toBeInTheDocument();
        });

        it('copies the generated code to the clipboard', async () => {
            const writeText = vi.fn().mockResolvedValue(undefined);
            Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
            vi.mocked(generateTenantInviteCode).mockResolvedValue(inviteFor('COPYME'));
            renderWithTenants([acmeTenant]);

            fireEvent.click(screen.getByRole('button', { name: 'Create invite code for Acme Corp' }));
            fireEvent.click(await screen.findByRole('button', { name: 'Copy invite code' }));

            await waitFor(() => expect(writeText).toHaveBeenCalledWith('COPYME'));
            await waitFor(() => expect(toast.success).toHaveBeenCalledWith('Code copied to clipboard'));
        });

        it('reports a failed copy with an error toast', async () => {
            const writeText = vi.fn().mockRejectedValue(new Error('denied'));
            Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
            vi.mocked(generateTenantInviteCode).mockResolvedValue(inviteFor('COPYME'));
            renderWithTenants([acmeTenant]);

            fireEvent.click(screen.getByRole('button', { name: 'Create invite code for Acme Corp' }));
            fireEvent.click(await screen.findByRole('button', { name: 'Copy invite code' }));

            await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Failed to copy'));
        });

        it('shows an error toast and no code when generation fails', async () => {
            vi.mocked(generateTenantInviteCode).mockRejectedValue(new Error('Tenant is inactive'));
            renderWithTenants([acmeTenant]);

            fireEvent.click(screen.getByRole('button', { name: 'Create invite code for Acme Corp' }));

            await waitFor(() => expect(toast.error).toHaveBeenCalled());
            expect(screen.queryByRole('button', { name: 'Copy invite code' })).not.toBeInTheDocument();
        });

        it('does not offer invite creation for a disabled tenant', () => {
            renderWithTenants([{ ...acmeTenant, is_active: false }]);

            expect(screen.getByRole('button', { name: 'Create invite code for Acme Corp' })).toBeDisabled();
        });
    });
});
