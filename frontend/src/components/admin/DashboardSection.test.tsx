import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../../api/adminSystem', () => ({
    getSystemStats: vi.fn(),
    getLoginActivity: vi.fn(),
}));

vi.mock('../../api/adminPrices', () => ({
    syncFinnhub: vi.fn(),
    syncYahoo: vi.fn(),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../../hooks/useApiQuery';
import { syncFinnhub } from '../../api/adminPrices';
import DashboardSection from './DashboardSection';

const mockUseApiQuery = vi.mocked(useApiQuery);

const stats = {
    total_users: 5,
    active_users: 4,
    total_tenants: 2,
    total_accounts: 10,
    total_holdings: 25,
    total_transactions: 400,
    database_size: '12 MB',
    symbols_tracked: 20,
    stale_symbols: 1,
};

const activity = [
    { user_email: 'a@x.com', tenant_id: 't-1', success: true, ip_address: '127.0.0.1', created_at: '2026-04-10T00:00:00Z' },
];

function setupMocks() {
    let call = 0;
    mockUseApiQuery.mockImplementation(() => {
        call++;
        if (call === 1) {
            return { data: stats, loading: false, error: null, refetch: vi.fn() };
        }
        return { data: activity, loading: false, error: null, refetch: vi.fn() };
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    }) as any;
}

describe('DashboardSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders system stats', () => {
        setupMocks();
        render(<DashboardSection />);
        expect(screen.getByText('5')).toBeInTheDocument(); // total_users
        expect(screen.getByText('12 MB')).toBeInTheDocument();
    });

    it('renders login activity rows', () => {
        setupMocks();
        render(<DashboardSection />);
        expect(screen.getByText('a@x.com')).toBeInTheDocument();
    });

    it('triggers finnhub sync', async () => {
        setupMocks();
        vi.mocked(syncFinnhub).mockResolvedValue({ succeeded: 5, total: 5, failures: [] });
        render(<DashboardSection />);
        fireEvent.click(screen.getByText(/Sync Finnhub/i));
        await waitFor(() => {
            expect(syncFinnhub).toHaveBeenCalled();
        });
    });
});
