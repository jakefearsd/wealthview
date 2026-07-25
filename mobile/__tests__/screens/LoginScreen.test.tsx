/**
 * @format
 */

import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockLogin = jest.fn();
const mockClearError = jest.fn();
const mockNavigate = jest.fn();

let mockAuthState: {
    serverUrl: string | null;
    error: string | null;
    status: string;
};

jest.mock('../../src/auth/AuthContext', () => ({
    __esModule: true,
    useAuth: () => ({
        ...mockAuthState,
        login: mockLogin,
        clearError: mockClearError,
    }),
}));

jest.mock('@react-navigation/native', () => ({
    __esModule: true,
    useNavigation: () => ({ navigate: mockNavigate }),
}));

import { LoginScreen } from '../../src/screens/LoginScreen';

beforeEach(() => {
    jest.clearAllMocks();
    mockAuthState = {
        serverUrl: 'https://wealthview.example.com',
        error: null,
        status: 'unauthenticated',
    };
    mockLogin.mockResolvedValue(undefined);
});

describe('LoginScreen', () => {
    it('renders email + password inputs and a sign-in button', async () => {
        const { getByTestId } = await render(<LoginScreen />);
        expect(getByTestId('email-input')).toBeTruthy();
        expect(getByTestId('password-input')).toBeTruthy();
        expect(getByTestId('sign-in-button')).toBeTruthy();
    });

    it('shows the configured server URL with a "Change" link', async () => {
        const { queryByText, getByTestId } = await render(<LoginScreen />);
        expect(queryByText(/wealthview\.example\.com/)).toBeTruthy();
        expect(getByTestId('change-server-link')).toBeTruthy();
    });

    it('navigates to Settings when "Change" is pressed', async () => {
        const { getByTestId } = await render(<LoginScreen />);
        await fireEvent.press(getByTestId('change-server-link'));
        expect(mockNavigate).toHaveBeenCalledWith('Settings');
    });

    it('calls login() with the entered credentials on submit', async () => {
        const { getByTestId } = await render(<LoginScreen />);

        await fireEvent.changeText(getByTestId('email-input'), 'demo@wealthview.local');
        await fireEvent.changeText(getByTestId('password-input'), 'demo123');
        await act(async () => {
            await fireEvent.press(getByTestId('sign-in-button'));
        });

        await waitFor(() => {
            expect(mockLogin).toHaveBeenCalledWith('demo@wealthview.local', 'demo123');
        });
    });

    it('does not call login() when the email or password is empty', async () => {
        const { getByTestId } = await render(<LoginScreen />);

        await act(async () => {
            await fireEvent.press(getByTestId('sign-in-button'));
        });

        expect(mockLogin).not.toHaveBeenCalled();
    });

    it('displays the server error message when login fails', async () => {
        mockAuthState.error = 'Invalid email or password';
        const { queryByText } = await render(<LoginScreen />);
        expect(queryByText(/invalid email or password/i)).toBeTruthy();
    });

    it('shows the MFA-not-supported note when AuthContext signals MFA was required', async () => {
        mockAuthState.error =
            'MFA is enabled on this account but mobile MFA is not yet supported in this build. Disable MFA on the web client to sign in here.';
        const { queryByText } = await render(<LoginScreen />);
        expect(queryByText(/MFA is enabled/i)).toBeTruthy();
    });
});
