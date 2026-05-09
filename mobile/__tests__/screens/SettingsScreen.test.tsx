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
    };
    mockSetServerUrl.mockResolvedValue(undefined);
    mockLogout.mockResolvedValue(undefined);
});

describe('SettingsScreen', () => {
    it('shows the current server URL pre-populated and a logout button when authenticated', () => {
        const { getByTestId } = render(<SettingsScreen />);
        expect(getByTestId('server-url-input').props.value).toBe(
            'https://wealthview.example.com',
        );
        expect(getByTestId('logout-button')).toBeTruthy();
    });

    it('hides the logout button when unauthenticated', () => {
        mockAuthState.status = 'unauthenticated';
        const { queryByTestId } = render(<SettingsScreen />);
        expect(queryByTestId('logout-button')).toBeNull();
    });

    it('rejects an invalid URL on save', async () => {
        const { getByTestId, queryByText } = render(<SettingsScreen />);
        fireEvent.changeText(getByTestId('server-url-input'), 'not-a-url');
        await act(async () => {
            fireEvent.press(getByTestId('save-server-button'));
        });
        expect(queryByText(/valid url/i)).toBeTruthy();
        expect(mockSetServerUrl).not.toHaveBeenCalled();
    });

    it('asks for confirmation before changing the URL while authenticated', async () => {
        const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
        const { getByTestId } = render(<SettingsScreen />);

        fireEvent.changeText(getByTestId('server-url-input'), 'https://other.example.com');
        await act(async () => {
            fireEvent.press(getByTestId('save-server-button'));
        });

        expect(alertSpy).toHaveBeenCalled();
        // Tap-through happens via the alert button; without simulating it, the
        // setter must not have been called yet.
        expect(mockSetServerUrl).not.toHaveBeenCalled();
        alertSpy.mockRestore();
    });

    it('skips confirmation when the user is unauthenticated', async () => {
        mockAuthState.status = 'unauthenticated';
        const { getByTestId } = render(<SettingsScreen />);

        fireEvent.changeText(getByTestId('server-url-input'), 'https://other.example.com');
        await act(async () => {
            fireEvent.press(getByTestId('save-server-button'));
        });

        await waitFor(() => {
            expect(mockSetServerUrl).toHaveBeenCalledWith('https://other.example.com');
        });
    });

    it('triggers logout when the logout button is pressed', async () => {
        const { getByTestId } = render(<SettingsScreen />);
        await act(async () => {
            fireEvent.press(getByTestId('logout-button'));
        });
        await waitFor(() => {
            expect(mockLogout).toHaveBeenCalled();
        });
    });
});
