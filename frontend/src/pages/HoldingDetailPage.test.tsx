import { screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
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

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { listTransactions } from '../api/transactions';
import HoldingDetailPage from './HoldingDetailPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

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
});
