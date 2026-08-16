import { screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

vi.mock('../api/holdings', () => ({
    getHolding: vi.fn(),
    updateHolding: vi.fn(),
}));

vi.mock('../api/transactions', () => ({
    listTransactions: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(), toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { listTransactions } from '../api/transactions';
import { updateHolding } from '../api/holdings';
import HoldingDetailPage from './HoldingDetailPage';
import { authAs } from '../testutil/auth';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockUseAuth = vi.mocked(useAuth);

const holding = {
    id: 'h-1',
    account_id: 'acc-1',
    symbol: 'AAPL',
    quantity: 10,
    cost_basis: 1500,
    current_price: 180,
    current_value: 1800,
    is_manual_override: false,
};

function renderPage() {
    return renderWithRoute(<HoldingDetailPage />, {
        path: '/holdings/:id',
        entry: '/holdings/h-1',
    });
}

describe('HoldingDetailPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: holding, loading: false, error: null, refetch: vi.fn() } as any);
        vi.mocked(listTransactions).mockResolvedValue({ data: [], total: 0, page: 0, size: 100 });
        mockUseAuth.mockReturnValue(authAs('admin'));
    });

    // === write gating ===
    //
    // PUT /api/v1/holdings/** is open to ADMIN, MEMBER and SUPER_ADMIN (SecurityConfig), so the
    // override editor must be offered to exactly those roles.

    it('shows the override editor to a super_admin', async () => {
        mockUseAuth.mockReturnValue(authAs('super_admin'));

        renderPage();
        await waitFor(() => expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0));

        expect(screen.getByRole('button', { name: /Edit Override/i })).toBeInTheDocument();
    });

    it('hides the override editor from a viewer', async () => {
        mockUseAuth.mockReturnValue(authAs('viewer'));

        renderPage();
        await waitFor(() => expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0));

        expect(screen.queryByRole('button', { name: /Edit Override/i })).not.toBeInTheDocument();
    });

    it('renders symbol and current value', async () => {
        renderPage();
        await waitFor(() => {
            expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0);
        });
    });

    it('renders the initial quantity and cost basis values', async () => {
        renderPage();
        await waitFor(() => {
            expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0);
        });
        // "10" appears somewhere — quantity is 10
        expect(screen.getAllByText(/\b10\b/).length).toBeGreaterThan(0);
    });

    // === manual override editing ===
    //
    // This page is the manual-override surface: whatever is saved here replaces the quantity and
    // cost basis that holdings recomputation would otherwise derive from transactions. The save
    // must carry the holding's OWN account and symbol — it is the only page where those are read
    // back off the loaded holding rather than supplied by the caller.

    const openEditor = async () => {
        renderPage();
        await waitFor(() => expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0));
        fireEvent.click(screen.getByRole('button', { name: /Edit Override/i }));
    };

    it('seeds the override editor from the loaded holding', async () => {
        await openEditor();

        expect(screen.getByDisplayValue('10')).toBeInTheDocument();
        expect(screen.getByDisplayValue('1500')).toBeInTheDocument();
    });

    it('saves the override against the holding\'s own account and symbol', async () => {
        vi.mocked(updateHolding).mockResolvedValue({} as never);
        await openEditor();

        fireEvent.change(screen.getByDisplayValue('10'), { target: { value: '14' } });
        fireEvent.change(screen.getByDisplayValue('1500'), { target: { value: '2100' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(updateHolding).toHaveBeenCalledWith('h-1', {
            account_id: 'acc-1',
            symbol: 'AAPL',
            quantity: 14,
            cost_basis: 2100,
        }));
    });

    it('closes the editor and confirms once the override is saved', async () => {
        vi.mocked(updateHolding).mockResolvedValue({} as never);
        await openEditor();

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Holding updated'));
        await waitFor(() =>
            expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument());
    });

    it('leaves the holding untouched when the edit is cancelled', async () => {
        await openEditor();

        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

        expect(updateHolding).not.toHaveBeenCalled();
        expect(screen.getByRole('button', { name: /Edit Override/i })).toBeInTheDocument();
    });

    it('keeps the editor open when the save fails', async () => {
        vi.mocked(updateHolding).mockRejectedValue(new Error('stale holding'));
        await openEditor();

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(toastError).toHaveBeenCalled());
        expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    });

    // === states ===

    it('reports a holding that does not exist', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() } as any);
        renderPage();

        expect(await screen.findByText('Holding not found')).toBeInTheDocument();
    });

    it('shows a loading state while the holding is in flight', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() } as any);
        renderPage();

        expect(screen.getByText(/Loading holding/i)).toBeInTheDocument();
    });

    it('reports whether the holding is a manual override', async () => {
        mockUseApiQuery.mockReturnValue({
            data: { ...holding, is_manual_override: true }, loading: false, error: null, refetch: vi.fn(),
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        renderPage();

        await waitFor(() => expect(screen.getByText('Manual Override')).toBeInTheDocument());
        expect(screen.getByText('Yes')).toBeInTheDocument();
    });
});
