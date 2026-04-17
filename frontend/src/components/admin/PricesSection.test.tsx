import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../api/adminPrices', () => ({
    getPriceStatus: vi.fn(),
    syncFinnhub: vi.fn(),
    syncYahoo: vi.fn(),
    fetchYahoo: vi.fn(),
    saveYahooPrices: vi.fn(),
    uploadPriceCsv: vi.fn(),
}));

vi.mock('../../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('./PriceBrowserTab', () => ({
    default: () => <div data-testid="price-browser" />,
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../../hooks/useApiQuery';
import { syncFinnhub } from '../../api/adminPrices';
import PricesSection from './PricesSection';

const mockUseApiQuery = vi.mocked(useApiQuery);

const status = [
    { symbol: 'AAPL', latest_date: '2026-04-10', source: 'finnhub', stale: false },
    { symbol: 'MSFT', latest_date: '2026-03-01', source: 'yahoo', stale: true },
];

describe('PricesSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: status, loading: false, error: null, refetch: vi.fn() } as any);
    });

    it('shows the symbol status table by default', () => {
        render(<PricesSection />);
        expect(screen.getByText('AAPL')).toBeInTheDocument();
        expect(screen.getByText('MSFT')).toBeInTheDocument();
    });

    it('switches to Yahoo tab', () => {
        render(<PricesSection />);
        fireEvent.click(screen.getByText('Yahoo Finance'));
        expect(screen.getAllByText(/Sync All Holdings from Yahoo/i).length).toBeGreaterThan(0);
    });

    it('triggers finnhub sync from the finnhub tab', async () => {
        vi.mocked(syncFinnhub).mockResolvedValue({ succeeded: 2, total: 2, failures: [] });
        render(<PricesSection />);

        const syncButton = screen.getByRole('button', { name: /^Sync All Holdings$/i });
        fireEvent.click(syncButton);
        await waitFor(() => {
            expect(syncFinnhub).toHaveBeenCalled();
        });
    });

    it('shows the Browse tab when selected', () => {
        render(<PricesSection />);
        fireEvent.click(screen.getByText('Browse'));
        expect(screen.getByTestId('price-browser')).toBeInTheDocument();
    });
});
