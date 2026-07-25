/**
 * @format
 */

import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { AccountResponse, DashboardSummaryResponse, PageResponse } from '@wealthview/shared';

const mockGetSummary = jest.fn();
const mockListAccounts = jest.fn();
const mockNavigate = jest.fn();
const mockGetDataApis = jest.fn();

jest.mock('@react-navigation/native', () => ({
    __esModule: true,
    useNavigation: () => ({ navigate: mockNavigate }),
    useFocusEffect: (cb: () => void) => {
        // jest.mock factories are hoisted above the import block, so React has to be
        // required lazily here; that necessarily shadows the module-level import.
        // eslint-disable-next-line @typescript-eslint/no-shadow
        const React = require('react');
        // Empty deps on purpose: the real useFocusEffect runs its callback when the screen
        // gains focus, which in a mounted-once test is exactly mount. Adding `cb` would
        // re-run it on every render, since the caller passes a fresh closure each time.
        React.useEffect(() => {
            const cleanup = cb();
            return typeof cleanup === 'function' ? cleanup : undefined;
            // eslint-disable-next-line react-hooks/exhaustive-deps
        }, []);
    },
}));

jest.mock('../../src/auth/AuthContext', () => ({
    __esModule: true,
    useAuth: () => ({
        identity: {
            user_id: 'u',
            tenant_id: 't',
            email: 'demo@x',
            role: 'admin',
        },
        getDataApis: mockGetDataApis,
    }),
}));

import { PortfolioScreen } from '../../src/screens/PortfolioScreen';

const ACCOUNT_BROKERAGE: AccountResponse = {
    id: 'a-broker',
    name: 'Fidelity Brokerage',
    type: 'brokerage',
    institution: 'Fidelity',
    currency: 'USD',
    balance: 456789.0,
    created_at: '2026-01-01T00:00:00Z',
};

const ACCOUNT_BANK: AccountResponse = {
    id: 'a-bank',
    name: 'Chase Checking',
    type: 'bank',
    institution: 'Chase',
    currency: 'USD',
    balance: 12345.67,
    created_at: '2026-01-01T00:00:00Z',
};

const SUMMARY: DashboardSummaryResponse = {
    net_worth: 1234567.89,
    total_investments: 890000.0,
    total_cash: 44567.89,
    total_property_equity: 300000.0,
    accounts: [],
    allocation: [
        { category: 'brokerage', value: 456789.0, percentage: 37.0 },
        { category: 'bank', value: 12345.67, percentage: 1.0 },
        { category: 'property', value: 300000.0, percentage: 24.0 },
    ],
};

const ACCOUNTS_PAGE: PageResponse<AccountResponse> = {
    data: [ACCOUNT_BROKERAGE, ACCOUNT_BANK],
    page: 0,
    size: 25,
    total: 2,
};

beforeEach(() => {
    jest.clearAllMocks();
    mockGetDataApis.mockReturnValue({
        dashboardApi: { getSummary: mockGetSummary },
        accountsApi: { list: mockListAccounts, get: jest.fn() },
    });
    mockGetSummary.mockResolvedValue(SUMMARY);
    mockListAccounts.mockResolvedValue(ACCOUNTS_PAGE);
});

describe('PortfolioScreen', () => {
    it('shows a loading indicator on first render before data resolves', async () => {
        // Hold the promise so we can observe the loading state.
        mockGetSummary.mockReturnValue(new Promise(() => {}));
        mockListAccounts.mockReturnValue(new Promise(() => {}));

        const { getByTestId } = await render(<PortfolioScreen />);
        expect(getByTestId('portfolio-loading')).toBeTruthy();
    });

    it('renders the net worth headline once data loads', async () => {
        const { findByText } = await render(<PortfolioScreen />);
        const headline = await findByText(/\$1,234,567\.89/);
        expect(headline).toBeTruthy();
    });

    it('renders an account row for each account from the API', async () => {
        const { findByText } = await render(<PortfolioScreen />);
        expect(await findByText('Fidelity Brokerage')).toBeTruthy();
        expect(await findByText('Chase Checking')).toBeTruthy();
    });

    it('renders a section header per category present in the data', async () => {
        const { findByText } = await render(<PortfolioScreen />);
        // Section headers are uppercased by the Section component.
        expect(await findByText('INVESTMENT ACCOUNTS')).toBeTruthy();
        expect(await findByText('CASH')).toBeTruthy();
    });

    it('navigates to AccountDetail when a row is tapped', async () => {
        const { findByTestId } = await render(<PortfolioScreen />);
        const row = await findByTestId(`account-row-${ACCOUNT_BROKERAGE.id}`);

        await fireEvent.press(row);

        expect(mockNavigate).toHaveBeenCalledWith('AccountDetail', {
            account: ACCOUNT_BROKERAGE,
        });
    });

    it('shows an error card with retry when the API fails, then refetches on retry', async () => {
        mockGetSummary.mockRejectedValueOnce(new Error('boom'));

        const { findByTestId, findByText } = await render(<PortfolioScreen />);
        const retry = await findByTestId('portfolio-retry');
        expect(await findByText(/couldn.t load/i)).toBeTruthy();

        // Make subsequent fetches succeed.
        mockGetSummary.mockResolvedValue(SUMMARY);
        mockListAccounts.mockResolvedValue(ACCOUNTS_PAGE);

        await act(async () => {
            await fireEvent.press(retry);
        });

        await waitFor(() => {
            expect(mockGetSummary).toHaveBeenCalledTimes(2);
        });
    });

    it('shows an empty state when the accounts list is empty', async () => {
        mockListAccounts.mockResolvedValue({ ...ACCOUNTS_PAGE, data: [], total: 0 });

        const { findByText } = await render(<PortfolioScreen />);
        expect(await findByText(/no accounts yet/i)).toBeTruthy();
    });
});
