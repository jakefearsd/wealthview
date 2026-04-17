import { screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
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

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import AccountDetailPage from './AccountDetailPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

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
    mockUseApiQuery.mockImplementation(() => {
        call++;
        if (call === 1) return { data: account, loading: acctLoading, error: null, refetch: vi.fn() };
        if (call === 2) return { data: holdings, loading: false, error: null, refetch: vi.fn() };
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
});
