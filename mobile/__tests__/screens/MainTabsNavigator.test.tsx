/**
 * @format
 */

import React from 'react';
import { render } from '@testing-library/react-native';

const mockGetSummary = jest.fn();
const mockListAccounts = jest.fn();
const mockGetDataApis = jest.fn();

jest.mock('@react-navigation/native', () => ({
    __esModule: true,
    useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn() }),
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
    useRoute: () => ({ params: {} }),
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
        serverUrl: 'https://wealthview.example.com',
        status: 'authenticated',
        getDataApis: mockGetDataApis,
    }),
}));

import { MainTabsNavigator } from '../../src/navigation/MainTabsNavigator';

beforeEach(() => {
    jest.clearAllMocks();
    mockGetDataApis.mockReturnValue({
        dashboardApi: { getSummary: mockGetSummary },
        accountsApi: { list: mockListAccounts, get: jest.fn() },
    });
    mockGetSummary.mockReturnValue(new Promise(() => {}));
    mockListAccounts.mockReturnValue(new Promise(() => {}));
});

describe('MainTabsNavigator', () => {
    it('mounts and lands on the Portfolio tab by default', () => {
        // The jest.setup.js stub for bottom-tabs renders only the first
        // configured tab, so finding the portfolio loading indicator (the
        // first frame of PortfolioScreen) confirms the default tab.
        const { getByTestId } = render(<MainTabsNavigator />);
        expect(getByTestId('portfolio-loading')).toBeTruthy();
    });
});
