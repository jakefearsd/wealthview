import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('../utils/format', () => ({
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

import PropertyTransactionForm from './PropertyTransactionForm';

const categories = [
    { value: 'rent', label: 'Rent' },
    { value: 'deposit', label: 'Deposit' },
];

describe('PropertyTransactionForm', () => {
    it('renders inputs and submit button with the title', () => {
        render(
            <PropertyTransactionForm title="Add Income" categories={categories} onSubmit={vi.fn().mockResolvedValue(true)} buttonColor="#2e7d32" />
        );
        expect(screen.getByPlaceholderText('Amount')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('Description')).toBeInTheDocument();
        expect(screen.getAllByRole('button', { name: 'Add Income' })).toHaveLength(1);
    });

    it('submits parsed values and resets', async () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        render(
            <PropertyTransactionForm title="Add Income" categories={categories} onSubmit={onSubmit} buttonColor="#2e7d32" />
        );

        const dateInput = document.querySelector('input[type="date"]') as HTMLInputElement;
        fireEvent.change(dateInput, { target: { value: '2026-04-10' } });
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '1500' } });
        fireEvent.change(screen.getByPlaceholderText('Description'), { target: { value: 'April rent' } });
        fireEvent.click(screen.getByRole('button', { name: 'Add Income' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalledWith({
                date: '2026-04-10',
                amount: 1500,
                category: 'rent',
                description: 'April rent',
                frequency: 'monthly',
            });
        });
        await waitFor(() => expect(screen.getByPlaceholderText('Amount')).toHaveValue(''));
        expect(dateInput.value).toBe('');
        expect(screen.getByPlaceholderText('Description')).toHaveValue('');
    });

    it('defaults category to first option', () => {
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={vi.fn().mockResolvedValue(true)} buttonColor="#d32f2f" />
        );
        const categorySelect = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
        expect(categorySelect.value).toBe('rent');
    });

    it('omits description when blank', async () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        render(
            <PropertyTransactionForm title="Add" categories={categories} onSubmit={onSubmit} buttonColor="#1976d2" />
        );
        fireEvent.change(document.querySelector('input[type="date"]') as HTMLInputElement, { target: { value: '2026-04-10' } });
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '10' } });
        fireEvent.click(screen.getByRole('button', { name: 'Add' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ description: undefined }));
        });
    });

    function fill(date: string, amount: string, description = '') {
        fireEvent.change(document.querySelector('input[type="date"]') as HTMLInputElement, { target: { value: date } });
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: amount } });
        if (description) {
            fireEvent.change(screen.getByPlaceholderText('Description'), { target: { value: description } });
        }
    }

    it('keeps the entered values when the save fails', async () => {
        const onSubmit = vi.fn().mockResolvedValue(false);
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={onSubmit} buttonColor="#d32f2f" />
        );
        fill('2026-04-10', '250', 'Roof patch');
        fireEvent.click(screen.getByRole('button', { name: 'Add Expense' }));

        await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
        await waitFor(() => expect(screen.getByRole('button', { name: 'Add Expense' })).toBeEnabled());
        expect(screen.getByPlaceholderText('Amount')).toHaveValue('250');
        expect(screen.getByPlaceholderText('Description')).toHaveValue('Roof patch');
    });

    it('does not submit and shows an error when the date is missing', () => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={onSubmit} buttonColor="#d32f2f" />
        );
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '250' } });
        fireEvent.click(screen.getByRole('button', { name: 'Add Expense' }));

        expect(onSubmit).not.toHaveBeenCalled();
        expect(screen.getByRole('alert')).toHaveTextContent('date');
    });

    it.each(['', '0', '-5'])('does not submit when the amount is "%s"', (amount) => {
        const onSubmit = vi.fn().mockResolvedValue(true);
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={onSubmit} buttonColor="#d32f2f" />
        );
        fill('2026-04-10', amount);
        fireEvent.click(screen.getByRole('button', { name: 'Add Expense' }));

        expect(onSubmit).not.toHaveBeenCalled();
        expect(screen.getByRole('alert')).toHaveTextContent('amount');
    });

    it('disables the submit button while the save is in flight', async () => {
        let resolve: (v: boolean) => void = () => {};
        const onSubmit = vi.fn().mockReturnValue(new Promise<boolean>((r) => { resolve = r; }));
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={onSubmit} buttonColor="#d32f2f" />
        );
        fill('2026-04-10', '250');
        fireEvent.click(screen.getByRole('button', { name: 'Add Expense' }));

        await waitFor(() => expect(screen.getByRole('button', { name: 'Add Expense' })).toBeDisabled());
        fireEvent.click(screen.getByRole('button', { name: 'Add Expense' }));
        expect(onSubmit).toHaveBeenCalledTimes(1);

        resolve(true);
        await waitFor(() => expect(screen.getByRole('button', { name: 'Add Expense' })).toBeEnabled());
    });

    it('gives every input an accessible name', () => {
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={vi.fn()} buttonColor="#d32f2f" />
        );
        expect(screen.getByLabelText('Date')).toBeInTheDocument();
        expect(screen.getByLabelText('Amount')).toBeInTheDocument();
        expect(screen.getByLabelText('Category')).toBeInTheDocument();
        expect(screen.getByLabelText('Frequency')).toBeInTheDocument();
        expect(screen.getByLabelText('Description')).toBeInTheDocument();
    });
});
