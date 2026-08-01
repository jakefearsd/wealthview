import { screen, fireEvent, waitFor } from '@testing-library/react';
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

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(), toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { createAccount, updateAccount, deleteAccount } from '../api/accounts';
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

    // === create vs update on one form ===
    //
    // The same form and the same mutation serve both paths, discriminated only by editingId.
    // If startEdit fails to set it, an "edit" silently CREATES a duplicate account instead of
    // updating the one the user opened — and the success toast would still say so.

    const renderList = (accounts = [sampleAccount]) => {
        mockUseApiQuery.mockReturnValue({ data: { data: accounts, total: accounts.length, page: 0, size: 100 },
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
            loading: false, error: null, refetch: vi.fn() } as any);
        renderWithRouter(<AccountsListPage />);
    };

    it('prefills the form from the account being edited', () => {
        renderList();

        fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

        expect(screen.getByDisplayValue('Fidelity Brokerage')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument();
    });

    it('updates the opened account rather than creating a second one', async () => {
        vi.mocked(updateAccount).mockResolvedValue(sampleAccount as never);
        renderList();
        fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

        fireEvent.change(screen.getByDisplayValue('Fidelity Brokerage'), { target: { value: 'Fidelity IRA' } });
        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() => expect(updateAccount).toHaveBeenCalledWith('acc-1',
            expect.objectContaining({ name: 'Fidelity IRA' })));
        expect(createAccount).not.toHaveBeenCalled();
        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Account updated'));
    });

    it('omits a blank institution instead of sending an empty string', async () => {
        mockCreateAccount.mockResolvedValue(sampleAccount as never);
        renderList([]);
        fireEvent.click(screen.getByRole('button', { name: 'New Account' }));

        fireEvent.change(screen.getByPlaceholderText('Name'), { target: { value: 'Cash' } });
        fireEvent.click(screen.getByRole('button', { name: 'Create' }));

        await waitFor(() => expect(createAccount).toHaveBeenCalledWith(
            expect.objectContaining({ institution: undefined })));
    });

    it('defaults the currency to USD when the field is cleared', async () => {
        mockCreateAccount.mockResolvedValue(sampleAccount as never);
        renderList([]);
        fireEvent.click(screen.getByRole('button', { name: 'New Account' }));

        fireEvent.change(screen.getByPlaceholderText('Name'), { target: { value: 'Cash' } });
        fireEvent.change(screen.getByPlaceholderText(/Currency/), { target: { value: '' } });
        fireEvent.click(screen.getByRole('button', { name: 'Create' }));

        await waitFor(() => expect(createAccount).toHaveBeenCalledWith(
            expect.objectContaining({ currency: 'USD' })));
    });

    it('clears the form after a successful save so the next open starts blank', async () => {
        vi.mocked(updateAccount).mockResolvedValue(sampleAccount as never);
        renderList();
        fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

        fireEvent.click(screen.getByRole('button', { name: 'Save' }));

        await waitFor(() =>
            expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument());
    });

    it('abandons an edit on cancel without saving', () => {
        renderList();
        fireEvent.click(screen.getByRole('button', { name: 'Edit' }));

        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

        expect(updateAccount).not.toHaveBeenCalled();
        expect(screen.queryByPlaceholderText('Name')).not.toBeInTheDocument();
    });

    // === delete ===

    it('deletes an account once confirmed', async () => {
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
        vi.mocked(deleteAccount).mockResolvedValue(undefined as never);
        renderList();

        fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

        await waitFor(() => expect(deleteAccount).toHaveBeenCalledWith('acc-1'));
        confirmSpy.mockRestore();
    });

    it('keeps the account when the delete confirmation is dismissed', () => {
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
        renderList();

        fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

        expect(deleteAccount).not.toHaveBeenCalled();
        confirmSpy.mockRestore();
    });

    it('reports a failed delete without removing the row', async () => {
        vi.spyOn(window, 'confirm').mockReturnValue(true);
        vi.mocked(deleteAccount).mockRejectedValue(new Error('in use'));
        renderList();

        fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

        await waitFor(() => expect(toastError).toHaveBeenCalled());
        expect(screen.getByText('Fidelity Brokerage')).toBeInTheDocument();
    });
});
