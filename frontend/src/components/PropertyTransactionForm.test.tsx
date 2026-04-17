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
            <PropertyTransactionForm title="Add Income" categories={categories} onSubmit={vi.fn()} buttonColor="#2e7d32" />
        );
        expect(screen.getByPlaceholderText('Amount')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('Description')).toBeInTheDocument();
        expect(screen.getAllByRole('button', { name: 'Add Income' })).toHaveLength(1);
    });

    it('submits parsed values and resets', async () => {
        const onSubmit = vi.fn().mockResolvedValue(undefined);
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
    });

    it('defaults category to first option', () => {
        render(
            <PropertyTransactionForm title="Add Expense" categories={categories} onSubmit={vi.fn()} buttonColor="#d32f2f" />
        );
        const categorySelect = screen.getAllByRole('combobox')[0] as HTMLSelectElement;
        expect(categorySelect.value).toBe('rent');
    });

    it('omits description when blank', async () => {
        const onSubmit = vi.fn().mockResolvedValue(undefined);
        render(
            <PropertyTransactionForm title="Add" categories={categories} onSubmit={onSubmit} buttonColor="#1976d2" />
        );
        fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '10' } });
        fireEvent.click(screen.getByRole('button', { name: 'Add' }));

        await waitFor(() => {
            expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ description: undefined }));
        });
    });
});
