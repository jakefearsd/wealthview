import { screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import type { Account } from '../types/account';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
}));

vi.mock('../api/accounts', () => ({
    listAccounts: vi.fn(),
    createAccount: vi.fn(),
    updateAccount: vi.fn(),
    deleteAccount: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    inputFieldStyle: {},
    selectStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { createAccount } from '../api/accounts';
import AccountsListPage from './AccountsListPage';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockCreateAccount = vi.mocked(createAccount);

const sampleAccount: Account = {
    id: 'acc-1',
    name: 'Fidelity Brokerage',
    type: 'brokerage',
    institution: 'Fidelity',
    currency: 'USD',
    balance: 125000,
    created_at: '2026-01-01T00:00:00Z',
};

function mockReturn(overrides: Partial<ReturnType<typeof useApiQuery>> = {}) {
    mockUseApiQuery.mockReturnValue({
        data: { data: [sampleAccount], total: 1, page: 0, page_size: 100 },
        loading: false,
        error: null,
        refetch: vi.fn(),
        ...overrides,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    } as any);
}

describe('AccountsListPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the list of accounts', () => {
        mockReturn();
        renderWithRouter(<AccountsListPage />);
        expect(screen.getByText('Fidelity Brokerage')).toBeInTheDocument();
        expect(screen.getByText('$125,000')).toBeInTheDocument();
    });

    it('shows loading state', () => {
        mockReturn({ loading: true, data: null });
        renderWithRouter(<AccountsListPage />);
        expect(screen.getByText(/Loading accounts/i)).toBeInTheDocument();
    });

    it('shows error state with retry', () => {
        const refetch = vi.fn();
        mockReturn({ error: 'boom', data: null, refetch });
        renderWithRouter(<AccountsListPage />);
        expect(screen.getByText('boom')).toBeInTheDocument();
    });

    it('submits the create form', async () => {
        mockReturn();
        mockCreateAccount.mockResolvedValue(sampleAccount);
        renderWithRouter(<AccountsListPage />);

        fireEvent.click(screen.getByText('New Account'));
        fireEvent.change(screen.getByPlaceholderText('Name'), { target: { value: 'New Acct' } });
        fireEvent.change(screen.getByPlaceholderText('Institution'), { target: { value: 'Schwab' } });
        fireEvent.click(screen.getByText('Create'));

        expect(mockCreateAccount).toHaveBeenCalledWith({
            name: 'New Acct',
            type: 'brokerage',
            institution: 'Schwab',
            currency: 'USD',
        });
    });
});
