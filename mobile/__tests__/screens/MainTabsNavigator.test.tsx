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
        const React = require('react');
        React.useEffect(() => {
            const cleanup = cb();
            return typeof cleanup === 'function' ? cleanup : undefined;
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
