import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { StockSplit } from '../../api/stockSplits';

vi.mock('../../api/stockSplits', () => ({
    listStockSplits: vi.fn(),
    createStockSplit: vi.fn(),
    unapplyStockSplit: vi.fn(),
    syncStockSplits: vi.fn(),
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(),
    toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import {
    listStockSplits,
    createStockSplit,
    unapplyStockSplit,
    syncStockSplits,
} from '../../api/stockSplits';
import StockSplitsSection from './StockSplitsSection';

const appleSplit: StockSplit = {
    id: 'split-1',
    symbol: 'AAPL',
    effective_date: '2020-08-31',
    numerator: 4,
    denominator: 1,
    source: 'finnhub',
    applied_at: '2026-03-05T09:30:00Z',
    notes: null,
};

/** Fills the manual-split form. Fields are matched by placeholder / input type, as rendered. */
function fillSplitForm({ symbol = 'NVDA', date = '2024-06-10', numerator = '10', denominator = '1' } = {}) {
    fireEvent.change(screen.getByPlaceholderText(/Symbol/), { target: { value: symbol } });
    const dateInput = document.querySelector('input[type="date"]') as HTMLInputElement;
    fireEvent.change(dateInput, { target: { value: date } });
    fireEvent.change(screen.getByPlaceholderText('Numerator'), { target: { value: numerator } });
    fireEvent.change(screen.getByPlaceholderText('Denominator'), { target: { value: denominator } });
}

describe('StockSplitsSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(listStockSplits).mockResolvedValue([appleSplit]);
    });

    afterEach(() => {
        vi.restoreAllMocks();
    });

    it('renders applied splits with the ratio and a readable source label', async () => {
        render(<StockSplitsSection />);

        expect(await screen.findByText('AAPL')).toBeInTheDocument();
        expect(screen.getByText('2020-08-31')).toBeInTheDocument();
        expect(screen.getByText('4:1')).toBeInTheDocument();
        expect(screen.getByText('Finnhub')).toBeInTheDocument();
    });

    it('shows an empty state when no splits have been applied', async () => {
        vi.mocked(listStockSplits).mockResolvedValue([]);
        render(<StockSplitsSection />);

        expect(await screen.findByText(/No splits have been applied yet/)).toBeInTheDocument();
    });

    // === manual entry ===

    it('submits a manual split with the symbol upper-cased and ratio coerced to numbers', async () => {
        vi.mocked(createStockSplit).mockResolvedValue({ ...appleSplit, symbol: 'NVDA' });
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fillSplitForm({ symbol: 'nvda' });
        fireEvent.click(screen.getByRole('button', { name: 'Add split' }));

        await waitFor(() => expect(createStockSplit).toHaveBeenCalledWith({
            symbol: 'NVDA',
            effective_date: '2024-06-10',
            numerator: 10,
            denominator: 1,
        }));
    });

    it('refetches the applied list after a successful manual split', async () => {
        vi.mocked(createStockSplit).mockResolvedValue(appleSplit);
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');
        expect(listStockSplits).toHaveBeenCalledTimes(1);

        fillSplitForm();
        fireEvent.click(screen.getByRole('button', { name: 'Add split' }));

        await waitFor(() => expect(listStockSplits).toHaveBeenCalledTimes(2));
    });

    // The ratio inputs carry min={1}, so a literal 0 is rejected by browser constraint validation
    // before submit fires and never reaches handleCreate. The JS guard's reachable case is a
    // CLEARED field: Number('') is 0, which is falsy, so an empty ratio must be caught here.
    it.each([
        ['a blank symbol', { symbol: '   ' }],
        ['no effective date', { date: '' }],
        ['an empty numerator', { numerator: '' }],
        ['an empty denominator', { denominator: '' }],
    ])('refuses to submit with %s', async (_label, overrides) => {
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fillSplitForm(overrides);
        fireEvent.click(screen.getByRole('button', { name: 'Add split' }));

        expect(createStockSplit).not.toHaveBeenCalled();
        expect(toastError).toHaveBeenCalledWith('Fill every field');
    });

    // === un-apply ===

    it('un-applies a split once the destructive action is confirmed', async () => {
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
        vi.mocked(unapplyStockSplit).mockResolvedValue(undefined);
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByRole('button', { name: 'Un-apply' }));

        expect(confirmSpy).toHaveBeenCalledWith(expect.stringContaining('AAPL'));
        await waitFor(() => expect(unapplyStockSplit).toHaveBeenCalledWith('split-1'));
    });

    it('does not un-apply when the confirmation is dismissed', async () => {
        // Un-applying rewrites historical transactions and prices, so a cancelled confirm must be
        // a genuine no-op rather than merely a skipped toast.
        vi.spyOn(window, 'confirm').mockReturnValue(false);
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByRole('button', { name: 'Un-apply' }));

        expect(unapplyStockSplit).not.toHaveBeenCalled();
    });

    it('warns in the confirmation that transactions and prices will be restored', async () => {
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByRole('button', { name: 'Un-apply' }));

        expect(confirmSpy).toHaveBeenCalledWith(
            expect.stringContaining('Transactions and prices will be restored'),
        );
    });

    // === sync ===

    it('triggers a manual sync and refetches', async () => {
        vi.mocked(syncStockSplits).mockResolvedValue({
            symbols_scanned: 12,
            splits_discovered: 3,
            splits_applied: 1,
            failed_symbols: [],
        });
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));

        await waitFor(() => expect(syncStockSplits).toHaveBeenCalled());
        await waitFor(() => expect(listStockSplits).toHaveBeenCalledTimes(2));
    });

    it('surfaces a sync failure without clearing the applied list', async () => {
        vi.mocked(syncStockSplits).mockRejectedValue(new Error('sync unavailable'));
        render(<StockSplitsSection />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByRole('button', { name: 'Sync now' }));

        await waitFor(() => expect(toastError).toHaveBeenCalled());
        expect(screen.getByText('AAPL')).toBeInTheDocument();
    });
});
