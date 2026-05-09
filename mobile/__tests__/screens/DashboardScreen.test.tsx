/**
 * @format
 */

import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockLogout = jest.fn();
const mockRefreshMe = jest.fn();
const mockNavigate = jest.fn();

let mockAuthState: {
    serverUrl: string | null;
    identity:
        | { user_id: string; tenant_id: string; email: string; role: string }
        | null;
    status: string;
};

jest.mock('../../src/auth/AuthContext', () => ({
    __esModule: true,
    useAuth: () => ({
        ...mockAuthState,
        logout: mockLogout,
        refreshMe: mockRefreshMe,
    }),
}));

jest.mock('@react-navigation/native', () => ({
    __esModule: true,
    useNavigation: () => ({ navigate: mockNavigate }),
    useFocusEffect: (cb: () => void) => {
        const React = require('react');
        React.useEffect(() => {
            const cleanup = cb();
            return typeof cleanup === 'function' ? cleanup : undefined;
        }, []);
    },
}));

import { DashboardScreen } from '../../src/screens/DashboardScreen';

beforeEach(() => {
    jest.clearAllMocks();
    mockAuthState = {
        serverUrl: 'https://wealthview.example.com',
        status: 'authenticated',
        identity: {
            user_id: '11111111-2222-3333-4444-555555555555',
            tenant_id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            email: 'demo@wealthview.local',
            role: 'admin',
        },
    };
    mockLogout.mockResolvedValue(undefined);
    mockRefreshMe.mockResolvedValue(undefined);
});

describe('DashboardScreen', () => {
    it('greets the user with their email', () => {
        const { queryByText } = render(<DashboardScreen />);
        expect(queryByText(/demo@wealthview\.local/)).toBeTruthy();
    });

    it('shows tenant id and role', () => {
        const { queryByText } = render(<DashboardScreen />);
        expect(queryByText(/admin/i)).toBeTruthy();
        expect(queryByText(/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/)).toBeTruthy();
    });

    it('shows the configured server URL', () => {
        const { queryByText } = render(<DashboardScreen />);
        expect(queryByText(/wealthview\.example\.com/)).toBeTruthy();
    });

    it('refreshes /me on focus', async () => {
        render(<DashboardScreen />);
        await waitFor(() => {
            expect(mockRefreshMe).toHaveBeenCalled();
        });
    });

    it('logs out when the logout button is pressed', async () => {
        const { getByTestId } = render(<DashboardScreen />);

        await act(async () => {
            fireEvent.press(getByTestId('logout-button'));
        });

        await waitFor(() => {
            expect(mockLogout).toHaveBeenCalled();
        });
    });

    it('navigates to Settings when the settings button is pressed', () => {
        const { getByTestId } = render(<DashboardScreen />);
        fireEvent.press(getByTestId('settings-button'));
        expect(mockNavigate).toHaveBeenCalledWith('Settings');
    });
});
