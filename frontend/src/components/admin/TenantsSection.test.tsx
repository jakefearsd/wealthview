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
import { createTenant, setTenantActive } from '../../api/admin';
import TenantsSection from './TenantsSection';

const mockUseApiQuery = vi.mocked(useApiQuery);

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
});
