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

const { toastFn, toastSuccess, toastError } = vi.hoisted(() => {
    const fn = vi.fn() as unknown as { (msg: string, opts?: unknown): void; success: ReturnType<typeof vi.fn>; error: ReturnType<typeof vi.fn> };
    fn.success = vi.fn();
    fn.error = vi.fn();
    return { toastFn: fn, toastSuccess: fn.success, toastError: fn.error };
});
vi.mock('react-hot-toast', () => ({ default: toastFn }));

import { useApiQuery } from '../../hooks/useApiQuery';
import { syncFinnhub, syncYahoo, fetchYahoo, saveYahooPrices, uploadPriceCsv } from '../../api/adminPrices';
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

    // === Yahoo tab: symbol parsing and the preview -> save flow ===
    //
    // The Yahoo tab was reached by one test that only asserted its heading rendered. Its symbol
    // parsing is the part worth pinning: the field is free text, split on commas, and a mis-parse
    // silently fetches prices for the wrong ticker or none at all.

    function openYahooTab() {
        render(<PricesSection />);
        fireEvent.click(screen.getByText('Yahoo Finance'));
    }

    it('parses the comma-separated symbol field, trimming and upper-casing each entry', async () => {
        vi.mocked(fetchYahoo).mockResolvedValue([]);
        openYahooTab();

        fireEvent.change(screen.getByPlaceholderText('FXAIX, VBTLX, BND'), {
            target: { value: ' fxaix , vbtlx ,, bnd , ' },
        });
        fireEvent.click(screen.getByRole('button', { name: /Fetch Preview/i }));

        await waitFor(() => expect(fetchYahoo).toHaveBeenCalledWith(
            expect.objectContaining({ symbols: ['FXAIX', 'VBTLX', 'BND'] }),
        ));
    });

    it('refuses to fetch when the symbol field holds only separators', async () => {
        openYahooTab();

        fireEvent.change(screen.getByPlaceholderText('FXAIX, VBTLX, BND'), {
            target: { value: '  , , ' },
        });
        fireEvent.click(screen.getByRole('button', { name: /Fetch Preview/i }));

        expect(fetchYahoo).not.toHaveBeenCalled();
        expect(toastError).toHaveBeenCalledWith('Enter at least one symbol');
    });

    it('renders the fetched preview and saves exactly those rows', async () => {
        const prices = [
            { symbol: 'FXAIX', date: '2026-04-09', close_price: 190.12 },
            { symbol: 'FXAIX', date: '2026-04-10', close_price: 191.45 },
        ];
        vi.mocked(fetchYahoo).mockResolvedValue(prices);
        vi.mocked(saveYahooPrices).mockResolvedValue({ saved: 2 } as never);
        openYahooTab();

        fireEvent.change(screen.getByPlaceholderText('FXAIX, VBTLX, BND'), { target: { value: 'FXAIX' } });
        fireEvent.click(screen.getByRole('button', { name: /Fetch Preview/i }));

        expect(await screen.findByText('2 prices fetched')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /Save All/i }));

        await waitFor(() => expect(saveYahooPrices).toHaveBeenCalledWith(prices));
    });

    it('reports an empty Yahoo result instead of showing an empty preview table', async () => {
        vi.mocked(fetchYahoo).mockResolvedValue([]);
        openYahooTab();

        fireEvent.change(screen.getByPlaceholderText('FXAIX, VBTLX, BND'), { target: { value: 'NOSUCH' } });
        fireEvent.click(screen.getByRole('button', { name: /Fetch Preview/i }));

        await waitFor(() => expect(toastFn).toHaveBeenCalledWith(
            'No prices returned for those symbols and dates',
        ));
        expect(screen.queryByText(/prices fetched/)).not.toBeInTheDocument();
    });

    it('reports per-symbol failures from a sync-all rather than claiming success', async () => {
        vi.mocked(syncYahoo).mockResolvedValue({
            inserted: 5,
            updated: 2,
            failures: [{ symbol: 'BADSYM', reason: 'no price data' }],
        } as never);
        openYahooTab();

        fireEvent.click(screen.getByRole('button', { name: /^Sync All Holdings from Yahoo$/i }));

        await waitFor(() => expect(toastError).toHaveBeenCalledWith(
            expect.stringContaining('BADSYM (no price data)'),
            expect.anything(),
        ));
        expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('reports a clean sync-all as a success', async () => {
        vi.mocked(syncYahoo).mockResolvedValue({ inserted: 5, updated: 2, failures: [] } as never);
        openYahooTab();

        fireEvent.click(screen.getByRole('button', { name: /^Sync All Holdings from Yahoo$/i }));

        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Inserted 5, updated 2.'));
    });

    // === CSV upload tab ===

    function uploadCsv() {
        render(<PricesSection />);
        fireEvent.click(screen.getByText('CSV Upload'));
        const input = document.querySelector('input[type="file"]') as HTMLInputElement;
        const file = new File(['symbol,date,close_price\nAAPL,2026-04-10,190.00'], 'prices.csv',
            { type: 'text/csv' });
        fireEvent.change(input, { target: { files: [file] } });
        return { input, file };
    }

    it('uploads a chosen CSV and reports the imported count', async () => {
        vi.mocked(uploadPriceCsv).mockResolvedValue({ imported: 1, errors: [] });

        const { file } = uploadCsv();

        await waitFor(() => expect(uploadPriceCsv).toHaveBeenCalledWith(file));
        expect(await screen.findByText('Imported 1 prices')).toBeInTheDocument();
    });

    it('lists per-row errors alongside the imported count', async () => {
        vi.mocked(uploadPriceCsv).mockResolvedValue({
            imported: 1,
            errors: ['row 3: unparseable date', 'row 7: missing symbol'],
        });

        uploadCsv();

        expect(await screen.findByText(/Errors \(2\)/)).toBeInTheDocument();
        expect(screen.getByText('row 3: unparseable date')).toBeInTheDocument();
        expect(screen.getByText('row 7: missing symbol')).toBeInTheDocument();
    });

    it('clears the file input after an upload so the same file can be retried', async () => {
        vi.mocked(uploadPriceCsv).mockResolvedValue({ imported: 1, errors: [] });

        const { input } = uploadCsv();

        await waitFor(() => expect(input.value).toBe(''));
    });
});
