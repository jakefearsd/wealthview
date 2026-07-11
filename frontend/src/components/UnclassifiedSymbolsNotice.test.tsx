import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/securities', () => ({
    setClassification: vi.fn(),
}));

import { setClassification } from '../api/securities';
import UnclassifiedSymbolsNotice from './UnclassifiedSymbolsNotice';

describe('UnclassifiedSymbolsNotice', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(setClassification).mockResolvedValue({ symbol: 'SPAXX', asset_class: 'cash' });
    });

    it('renders one row per unclassified symbol', () => {
        render(<UnclassifiedSymbolsNotice symbols={['SPAXX', 'FZFXX']} onReclassified={vi.fn()} />);

        expect(screen.getByText(/These holdings were modeled as US Stock/)).toBeInTheDocument();
        expect(screen.getByText('SPAXX')).toBeInTheDocument();
        expect(screen.getByText('FZFXX')).toBeInTheDocument();
    });

    it('applies the chosen asset class per symbol and calls onReclassified once all resolve', async () => {
        const onReclassified = vi.fn();
        render(<UnclassifiedSymbolsNotice symbols={['SPAXX']} onReclassified={onReclassified} />);

        fireEvent.change(screen.getByLabelText('Asset class for SPAXX'), { target: { value: 'cash' } });
        fireEvent.click(screen.getByRole('button', { name: /apply/i }));

        await waitFor(() => {
            expect(setClassification).toHaveBeenCalledWith('SPAXX', 'cash');
        });
        await waitFor(() => {
            expect(onReclassified).toHaveBeenCalledTimes(1);
        });
    });

    it('applies distinct selections for multiple symbols before calling onReclassified', async () => {
        const onReclassified = vi.fn();
        render(<UnclassifiedSymbolsNotice symbols={['SPAXX', 'FZFXX']} onReclassified={onReclassified} />);

        fireEvent.change(screen.getByLabelText('Asset class for SPAXX'), { target: { value: 'cash' } });
        fireEvent.change(screen.getByLabelText('Asset class for FZFXX'), { target: { value: 'intl_stock' } });
        fireEvent.click(screen.getByRole('button', { name: /apply/i }));

        await waitFor(() => {
            expect(setClassification).toHaveBeenCalledWith('SPAXX', 'cash');
            expect(setClassification).toHaveBeenCalledWith('FZFXX', 'intl_stock');
        });
        await waitFor(() => {
            expect(onReclassified).toHaveBeenCalledTimes(1);
        });
    });
});
