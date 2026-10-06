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

vi.mock('../api/accounts', () => ({
    getAccount: vi.fn(),
}));

vi.mock('../api/transactions', () => ({
    listTransactions: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number, c = 'USD') => `${c === 'USD' ? '$' : `${c} `}${v.toLocaleString()}`,
    formatDate: (v: string | null | undefined) => (v ?? '--').slice(0, 10),
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
    as_of_date: '2026-03-01',
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

    // === load failures, currency and ordering ===
    //
    // The page issues three queries per render in a fixed order: holding, transactions, account.

    const txn = (over: Record<string, unknown>) => ({
        id: 't', account_id: 'acc-1', date: '2026-01-01', type: 'buy', symbol: 'AAPL',
        quantity: 1, amount: 100, created_at: '2026-01-01T00:00:00Z', ...over,
    });

    function setupQueries({ holdingResult = {}, txnResult = {}, accountResult = {} }: {
        holdingResult?: object; txnResult?: object; accountResult?: object;
    }) {
        let call = 0;
        mockUseApiQuery.mockImplementation(() => {
            const idx = call % 3;
            call++;
            const base = { data: null, loading: false, error: null, refetch: vi.fn() };
            if (idx === 0) return { ...base, data: holding, ...holdingResult };
            if (idx === 1) return { ...base, data: { data: [], total: 0, page: 0, size: 100 }, ...txnResult };
            return { ...base, data: { id: 'acc-1', currency: 'USD' }, ...accountResult };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        }) as any;
    }

    it('shows a retryable error, not "Holding not found", when the holding request fails', () => {
        const refetch = vi.fn();
        setupQueries({ holdingResult: { data: null, error: 'server down', refetch } });
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

        expect(screen.getByText('server down')).toBeInTheDocument();
        expect(screen.queryByText('Holding not found')).not.toBeInTheDocument();
        expect(refetch).toHaveBeenCalled();
    });

    it('shows an error instead of "No transactions" when the transaction request fails', () => {
        const refetch = vi.fn();
        setupQueries({ txnResult: { data: null, error: 'txn boom', refetch } });
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

        expect(screen.getByText('txn boom')).toBeInTheDocument();
        expect(screen.queryByText('No transactions for AAPL')).not.toBeInTheDocument();
        expect(refetch).toHaveBeenCalled();
    });

    it('keeps the page on screen while the holding is refetching', () => {
        setupQueries({ holdingResult: { loading: true } });
        renderPage();

        expect(screen.queryByText(/Loading holding/i)).not.toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'AAPL' })).toBeInTheDocument();
    });

    it('formats amounts in the account currency and names it beside the symbol', () => {
        setupQueries({
            accountResult: { data: { id: 'acc-1', currency: 'EUR' } },
            txnResult: { data: { data: [txn({ amount: 250 })], total: 1, page: 0, size: 100 } },
        });
        renderPage();

        expect(screen.getByText('EUR 1,500')).toBeInTheDocument();
        expect(screen.getByText('EUR 250')).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: /AAPL.*EUR/ })).toBeInTheDocument();
    });

    it('lists transactions newest first with a capitalised type', () => {
        setupQueries({
            txnResult: {
                data: {
                    data: [
                        txn({ id: 'a', date: '2025-02-02', type: 'dividend' }),
                        txn({ id: 'b', date: '2026-05-05', type: 'sell' }),
                    ],
                    total: 2, page: 0, size: 100,
                },
            },
        });
        renderPage();

        const rows = screen.getAllByRole('row').map((r) => r.textContent ?? '');
        expect(rows.findIndex((r) => r.includes('Sell'))).toBeLessThan(rows.findIndex((r) => r.includes('Dividend')));
    });

    it('says when only the most recent transactions are shown', () => {
        setupQueries({ txnResult: { data: { data: [txn({})], total: 250, page: 0, size: 100 } } });
        renderPage();

        expect(screen.getByText(/Showing the 1 most recent of 250 transactions/)).toBeInTheDocument();
    });

    it('blocks saving an override with a blank quantity and associates the labels with their inputs', async () => {
        await openEditor();

        fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '' } });

        expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
        expect(screen.getByLabelText('Cost Basis')).toBeInTheDocument();
    });
});
