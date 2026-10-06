import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/transactions', () => ({
    createTransaction: vi.fn(),
    updateTransaction: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { createTransaction, updateTransaction } from '../api/transactions';
import TransactionForm from './TransactionForm';

describe('TransactionForm', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders fields with default buy type', () => {
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);
        expect(screen.getByPlaceholderText('Symbol')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('Quantity')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('Amount')).toBeInTheDocument();
        const select = screen.getByRole('combobox') as HTMLSelectElement;
        expect(select.value).toBe('buy');
    });

    it('calls createTransaction on save', async () => {
        vi.mocked(createTransaction).mockResolvedValue({} as never);
        const onSuccess = vi.fn();
        render(<TransactionForm accountId="acc-1" onSuccess={onSuccess} onCancel={vi.fn()} />);

        const dateInput = document.querySelector('input[type="date"]') as HTMLInputElement;
        fireEvent.change(dateInput, { target: { value: '2026-04-10' } });
        fireEvent.change(screen.getByPlaceholderText('Symbol'), { target: { value: 'AAPL' } });
        fireEvent.change(screen.getByPlaceholderText('Quantity'), { target: { value: '10' } });
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '2000' } });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => {
            expect(createTransaction).toHaveBeenCalledWith('acc-1', {
                date: '2026-04-10',
                type: 'buy',
                symbol: 'AAPL',
                quantity: 10,
                amount: 2000,
            });
        });
        expect(onSuccess).toHaveBeenCalled();
    });

    it('calls updateTransaction when editing', async () => {
        vi.mocked(updateTransaction).mockResolvedValue({} as never);
        const initial = {
            id: 't-1',
            account_id: 'acc-1',
            date: '2026-01-01',
            type: 'sell',
            symbol: 'MSFT',
            quantity: 5,
            amount: 1500,
        };
        render(
            <TransactionForm
                accountId="acc-1"
                onSuccess={vi.fn()}
                onCancel={vi.fn()}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                initialValues={initial as any}
            />
        );
        fireEvent.click(screen.getByText('Save'));
        await waitFor(() => {
            expect(updateTransaction).toHaveBeenCalled();
        });
    });

    it('invokes onCancel', () => {
        const onCancel = vi.fn();
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={onCancel} />);
        fireEvent.click(screen.getByText('Cancel'));
        expect(onCancel).toHaveBeenCalled();
    });

    function fillValid(overrides: { symbol?: string; quantity?: string; amount?: string } = {}) {
        fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-04-10' } });
        fireEvent.change(screen.getByLabelText('Symbol'), { target: { value: overrides.symbol ?? 'AAPL' } });
        fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: overrides.quantity ?? '10' } });
        fireEvent.change(screen.getByLabelText('Amount'), { target: { value: overrides.amount ?? '2000' } });
    }

    it('trims and upper-cases the symbol so it matches the price-keyed holding', async () => {
        vi.mocked(createTransaction).mockResolvedValue({} as never);
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);

        fillValid({ symbol: ' aapl ' });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => expect(createTransaction).toHaveBeenCalledWith(
            'acc-1', expect.objectContaining({ symbol: 'AAPL' })));
    });

    it('omits a whitespace-only symbol', async () => {
        vi.mocked(createTransaction).mockResolvedValue({} as never);
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);

        fillValid({ symbol: '   ', quantity: '' });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => expect(createTransaction).toHaveBeenCalledWith(
            'acc-1', expect.objectContaining({ symbol: undefined, quantity: undefined })));
    });

    it('disables Save until a date and a numeric amount are present', () => {
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);
        expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

        fillValid();

        expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled();
    });

    it('shows an inline message and blocks the save when the amount is cleared', () => {
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);
        fillValid();

        fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '' } });

        expect(screen.getByRole('alert')).toHaveTextContent('Amount must be a number.');
        expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('rejects a negative quantity', () => {
        render(<TransactionForm accountId="acc-1" onSuccess={vi.fn()} onCancel={vi.fn()} />);
        fillValid({ quantity: '-3' });

        expect(screen.getByRole('alert')).toHaveTextContent('Quantity must be zero or more.');
        expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    });
});
