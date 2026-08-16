import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

vi.mock('../api/accounts', () => ({ getAccount: vi.fn() }));
vi.mock('../api/transactions', () => ({ listTransactions: vi.fn(), deleteTransaction: vi.fn() }));
vi.mock('../api/holdings', () => ({ listHoldings: vi.fn(), updateHolding: vi.fn() }));

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

vi.mock('../components/TheoreticalPortfolioChart', () => ({
    default: () => <div data-testid="theoretical-chart" />,
}));

vi.mock('../components/TransactionForm', () => ({
    default: () => <div data-testid="transaction-form" />,
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(), toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { updateHolding } from '../api/holdings';
import { deleteTransaction } from '../api/transactions';
import AccountDetailPage from './AccountDetailPage';
import { authAs } from '../testutil/auth';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockUseAuth = vi.mocked(useAuth);

const account = {
    id: 'acc-1',
    name: 'Fidelity Brokerage',
    type: 'brokerage',
    institution: 'Fidelity',
    currency: 'USD',
    balance: 125000,
    created_at: '2026-01-01T00:00:00Z',
};

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

const txn = {
    id: 't-1',
    account_id: 'acc-1',
    date: '2026-03-01',
    type: 'buy',
    symbol: 'AAPL',
    quantity: 10,
    amount: 1500,
};

function setupMocks({
    acctLoading = false,
    holdings = [holding],
    transactions = [txn],
}: { acctLoading?: boolean; holdings?: unknown[]; transactions?: unknown[] } = {}) {
    let call = 0;
    // The page issues three queries per render, always in this order. Cycling with modulo (rather
    // than a monotonic counter) keeps the mapping correct across RE-renders — without it the first
    // interaction shifts every query onto the next one's data and `holdings` becomes a page object.
    mockUseApiQuery.mockImplementation(() => {
        const idx = call % 3;
        call++;
        if (idx === 0) return { data: account, loading: acctLoading, error: null, refetch: vi.fn() };
        if (idx === 1) return { data: holdings, loading: false, error: null, refetch: vi.fn() };
        return { data: { data: transactions, total: transactions.length, page: 0, page_size: 50 }, loading: false, error: null, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

function renderPage() {
    return renderWithRoute(<AccountDetailPage />, {
        path: '/accounts/:id',
        entry: '/accounts/acc-1',
    });
}

describe('AccountDetailPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockUseAuth.mockReturnValue(authAs('admin'));
    });

    // === write gating ===
    //
    // The server allows POST/PUT/DELETE /api/v1/** to ADMIN, MEMBER and SUPER_ADMIN
    // (SecurityConfig), so the client gate has to match that set exactly.

    it('shows the add-transaction control to a super_admin', () => {
        mockUseAuth.mockReturnValue(authAs('super_admin'));
        setupMocks();

        renderPage();

        expect(screen.getByRole('button', { name: 'Add Transaction' })).toBeInTheDocument();
    });

    it('hides the add-transaction control from a viewer', () => {
        mockUseAuth.mockReturnValue(authAs('viewer'));
        setupMocks();

        renderPage();

        expect(screen.queryByRole('button', { name: 'Add Transaction' })).not.toBeInTheDocument();
    });

    it('renders the account header', () => {
        setupMocks();
        renderPage();
        expect(screen.getByText('Fidelity Brokerage')).toBeInTheDocument();
    });

    it('renders the holdings list', () => {
        setupMocks();
        renderPage();
        expect(screen.getAllByText('AAPL').length).toBeGreaterThan(0);
    });

    it('shows loading state while account loads', () => {
        setupMocks({ acctLoading: true });
        renderPage();
        expect(screen.getByText(/Loading/i)).toBeInTheDocument();
    });

    // === inline holding edit ===
    //
    // Editing a holding overwrites its quantity and cost basis, which feed every downstream
    // portfolio total and the projection's starting balances. The edit is inline and its two
    // fields are seeded from the row, so a wrong seed silently saves the previous row's numbers.

    const holdingRow = () => screen.getByRole('link', { name: 'AAPL' }).closest('tr')!;

    it('seeds the inline editor from the row being edited', () => {
        setupMocks();
        renderPage();

        fireEvent.click(within(holdingRow()).getByRole('button', { name: 'Edit' }));

        expect(screen.getByDisplayValue('10')).toBeInTheDocument();
        expect(screen.getByDisplayValue('1500')).toBeInTheDocument();
    });

    it('saves the edited quantity and cost basis against the account and symbol', async () => {
        setupMocks();
        vi.mocked(updateHolding).mockResolvedValue({} as never);
        renderPage();
        fireEvent.click(within(holdingRow()).getByRole('button', { name: 'Edit' }));

        fireEvent.change(screen.getByDisplayValue('10'), { target: { value: '12.5' } });
        fireEvent.change(screen.getByDisplayValue('1500'), { target: { value: '1900' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(updateHolding).toHaveBeenCalledWith('h-1', {
            account_id: 'acc-1',
            symbol: 'AAPL',
            quantity: 12.5,
            cost_basis: 1900,
        }));
    });

    it('closes the inline editor after a successful save', async () => {
        setupMocks();
        vi.mocked(updateHolding).mockResolvedValue({} as never);
        renderPage();
        fireEvent.click(within(holdingRow()).getByRole('button', { name: 'Edit' }));

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument());
    });

    it('abandons the edit on cancel without calling the API', () => {
        setupMocks();
        renderPage();
        fireEvent.click(within(holdingRow()).getByRole('button', { name: 'Edit' }));

        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

        expect(updateHolding).not.toHaveBeenCalled();
        expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    });

    it('keeps the editor open and reports the failure when the save is rejected', async () => {
        setupMocks();
        vi.mocked(updateHolding).mockRejectedValue(new Error('conflict'));
        renderPage();
        fireEvent.click(within(holdingRow()).getByRole('button', { name: 'Edit' }));

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(toastError).toHaveBeenCalled());
        expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    });


    // === totals row ===

    it('totals cost basis and market value separately, and the gain between them', () => {
        setupMocks({
            holdings: [
                { ...holding, id: 'h-1', symbol: 'AAPL', cost_basis: 1500, market_value: 1800 },
                { ...holding, id: 'h-2', symbol: 'MSFT', cost_basis: 2500, market_value: 2400 },
            ],
        });
        renderPage();

        const totalRow = within(screen.getByText('Total').closest('tr')!);
        expect(totalRow.getByText('$4,000')).toBeInTheDocument();  // 1,500 + 2,500 basis
        expect(totalRow.getByText('$4,200')).toBeInTheDocument();  // 1,800 + 2,400 value
        expect(totalRow.getByText('$200')).toBeInTheDocument();    // and the gain between them
    });

    it('falls back to cost basis when a holding has no market value', () => {
        setupMocks({
            holdings: [{ ...holding, cost_basis: 1500, market_value: null }],
        });
        renderPage();

        const totalRow = screen.getByText('Total').closest('tr')!;
        // An unpriced holding contributes its basis to value, so the gain/loss reads flat.
        expect(within(totalRow).getAllByText('$1,500').length).toBeGreaterThanOrEqual(1);
    });

    // === transactions ===

    it('deletes a transaction and refetches the list', async () => {
        setupMocks();
        vi.mocked(deleteTransaction).mockResolvedValue(undefined as never);
        renderPage();

        const deleteButtons = screen.getAllByRole('button', { name: /Delete/i });
        fireEvent.click(deleteButtons[0]);

        await waitFor(() => expect(deleteTransaction).toHaveBeenCalledWith('t-1'));
        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Transaction deleted'));
    });
});
