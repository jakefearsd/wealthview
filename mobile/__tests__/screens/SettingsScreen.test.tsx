/**
 * @format
 */

import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockSetServerUrl = jest.fn();
const mockLogout = jest.fn();

let mockAuthState: {
    serverUrl: string | null;
    status: string;
    identity:
        | { user_id: string; tenant_id: string; email: string; role: string }
        | null;
};

jest.mock('../../src/auth/AuthContext', () => ({
    __esModule: true,
    useAuth: () => ({
        ...mockAuthState,
        setServerUrl: mockSetServerUrl,
        logout: mockLogout,
    }),
}));

import { SettingsScreen } from '../../src/screens/SettingsScreen';

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
    mockSetServerUrl.mockResolvedValue(undefined);
    mockLogout.mockResolvedValue(undefined);
});

describe('SettingsScreen', () => {
    it('shows the current server URL pre-populated and a logout button when authenticated', async () => {
        const { getByTestId } = await render(<SettingsScreen />);
        expect(getByTestId('server-url-input').props.value).toBe(
            'https://wealthview.example.com',
        );
        expect(getByTestId('logout-button')).toBeTruthy();
    });

    it('hides the logout button when unauthenticated', async () => {
        mockAuthState.status = 'unauthenticated';
        const { queryByTestId } = await render(<SettingsScreen />);
        expect(queryByTestId('logout-button')).toBeNull();
    });

    it('rejects an invalid URL on save', async () => {
        const { getByTestId, queryByText } = await render(<SettingsScreen />);
        await fireEvent.changeText(getByTestId('server-url-input'), 'not-a-url');
        await act(async () => {
            await fireEvent.press(getByTestId('save-server-button'));
        });
        expect(queryByText(/valid url/i)).toBeTruthy();
        expect(mockSetServerUrl).not.toHaveBeenCalled();
    });

    it('asks for confirmation before changing the URL while authenticated', async () => {
        const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
        const { getByTestId } = await render(<SettingsScreen />);

        await fireEvent.changeText(getByTestId('server-url-input'), 'https://other.example.com');
        await act(async () => {
            await fireEvent.press(getByTestId('save-server-button'));
        });

        expect(alertSpy).toHaveBeenCalled();
        // Tap-through happens via the alert button; without simulating it, the
        // setter must not have been called yet.
        expect(mockSetServerUrl).not.toHaveBeenCalled();
        alertSpy.mockRestore();
    });

    it('skips confirmation when the user is unauthenticated', async () => {
        mockAuthState.status = 'unauthenticated';
        const { getByTestId } = await render(<SettingsScreen />);

        await fireEvent.changeText(getByTestId('server-url-input'), 'https://other.example.com');
        await act(async () => {
            await fireEvent.press(getByTestId('save-server-button'));
        });

        await waitFor(() => {
            expect(mockSetServerUrl).toHaveBeenCalledWith('https://other.example.com');
        });
    });

    it('triggers logout when the logout button is pressed', async () => {
        const { getByTestId } = await render(<SettingsScreen />);
        await act(async () => {
            await fireEvent.press(getByTestId('logout-button'));
        });
        await waitFor(() => {
            expect(mockLogout).toHaveBeenCalled();
        });
    });

    it('displays the signed-in identity (email, role, tenant id) when authenticated', async () => {
        const { queryByText } = await render(<SettingsScreen />);
        expect(queryByText('demo@wealthview.local')).toBeTruthy();
        expect(queryByText(/admin/)).toBeTruthy();
        expect(queryByText('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')).toBeTruthy();
    });

    it('omits the Account card when unauthenticated', async () => {
        mockAuthState.status = 'unauthenticated';
        mockAuthState.identity = null;
        const { queryByText } = await render(<SettingsScreen />);
        expect(queryByText('demo@wealthview.local')).toBeNull();
    });
});
