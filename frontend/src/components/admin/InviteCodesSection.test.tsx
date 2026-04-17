import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../api/tenant', () => ({
    listInviteCodes: vi.fn(),
    generateInviteCodeWithExpiry: vi.fn(),
    revokeInviteCode: vi.fn(),
    deleteUsedCodes: vi.fn(),
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
import {
    generateInviteCodeWithExpiry,
    revokeInviteCode,
} from '../../api/tenant';
import InviteCodesSection from './InviteCodesSection';

const mockUseApiQuery = vi.mocked(useApiQuery);
const futureIso = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();

const activeCode = {
    id: 'c1',
    code: 'ABC12345',
    expires_at: futureIso,
    consumed: false,
    is_revoked: false,
    used_by_email: null,
    created_by_email: 'admin@example.com',
    created_at: '2026-04-01T00:00:00Z',
};

describe('InviteCodesSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders invite codes with status labels', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [activeCode], loading: false, error: null, refetch: vi.fn() } as any);
        render(<InviteCodesSection />);
        expect(screen.getByText('ABC12345')).toBeInTheDocument();
        expect(screen.getByText('Active')).toBeInTheDocument();
    });

    it('generates a new code with the selected expiry', async () => {
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch } as any);
        vi.mocked(generateInviteCodeWithExpiry).mockResolvedValue(activeCode);
        render(<InviteCodesSection />);

        fireEvent.change(screen.getByRole('combobox'), { target: { value: '30' } });
        fireEvent.click(screen.getByText('Generate Code'));

        await waitFor(() => {
            expect(generateInviteCodeWithExpiry).toHaveBeenCalledWith(30);
        });
    });

    it('revokes a code when asked', async () => {
        vi.spyOn(window, 'confirm').mockReturnValue(true);
        const refetch = vi.fn();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [activeCode], loading: false, error: null, refetch } as any);
        vi.mocked(revokeInviteCode).mockResolvedValue(undefined);
        render(<InviteCodesSection />);

        fireEvent.click(screen.getByText('Revoke'));
        await waitFor(() => {
            expect(revokeInviteCode).toHaveBeenCalledWith('c1');
        });
    });

    it('renders empty state when no codes exist', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() } as any);
        render(<InviteCodesSection />);
        expect(screen.queryByText('ABC12345')).not.toBeInTheDocument();
    });
});
