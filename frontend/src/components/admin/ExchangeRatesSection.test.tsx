import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ExchangeRate } from '../../types/exchangeRate';

vi.mock('../../api/exchangeRates', () => ({
    listExchangeRates: vi.fn(),
    createExchangeRate: vi.fn(),
    updateExchangeRate: vi.fn(),
    deleteExchangeRate: vi.fn(),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    inputFieldStyle: {},
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(),
    toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import {
    listExchangeRates,
    createExchangeRate,
    updateExchangeRate,
    deleteExchangeRate,
} from '../../api/exchangeRates';
import ExchangeRatesSection from './ExchangeRatesSection';

const rate: ExchangeRate = {
    currency_code: 'EUR',
    rate_to_usd: 1.08,
    updated_at: '2026-04-10T00:00:00Z',
};

describe('ExchangeRatesSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(listExchangeRates).mockResolvedValue([rate]);
    });

    it('loads and renders rates', async () => {
        render(<ExchangeRatesSection />);
        expect(await screen.findByText('EUR')).toBeInTheDocument();
        expect(screen.getByText(/1 EUR = 1.08 USD/)).toBeInTheDocument();
    });

    it('opens add form and submits', async () => {
        vi.mocked(createExchangeRate).mockResolvedValue(rate);
        render(<ExchangeRatesSection />);
        await screen.findByText('EUR');

        fireEvent.click(screen.getByText('Add Currency'));
        fireEvent.change(screen.getByPlaceholderText('EUR'), { target: { value: 'gbp' } });
        fireEvent.change(screen.getByPlaceholderText('1.08'), { target: { value: '1.27' } });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => {
            expect(createExchangeRate).toHaveBeenCalledWith({
                currency_code: 'GBP',
                rate_to_usd: 1.27,
            });
        });
    });

    it('edits an existing rate', async () => {
        vi.mocked(updateExchangeRate).mockResolvedValue(rate);
        render(<ExchangeRatesSection />);
        await screen.findByText('EUR');

        fireEvent.click(screen.getByText('Edit'));
        const input = screen.getByDisplayValue('1.08');
        fireEvent.change(input, { target: { value: '1.10' } });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => {
            expect(updateExchangeRate).toHaveBeenCalledWith('EUR', {
                currency_code: 'EUR',
                rate_to_usd: 1.10,
            });
        });
    });

    it('deletes a rate after confirm', async () => {
        vi.spyOn(window, 'confirm').mockReturnValue(true);
        vi.mocked(deleteExchangeRate).mockResolvedValue(undefined);
        render(<ExchangeRatesSection />);
        await screen.findByText('EUR');

        fireEvent.click(screen.getByText('Delete'));

        await waitFor(() => {
            expect(deleteExchangeRate).toHaveBeenCalledWith('EUR');
        });
    });

    it('shows an error toast when loading fails', async () => {
        vi.mocked(listExchangeRates).mockRejectedValueOnce(new Error('boom'));
        render(<ExchangeRatesSection />);
        await waitFor(() => {
            expect(toastError).toHaveBeenCalled();
        });
        expect(toastError.mock.calls[0][0]).toMatch(/Failed to load exchange rates/);
    });
});
