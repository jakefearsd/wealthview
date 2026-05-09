/**
 * @format
 */

import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockSetServerUrl = jest.fn();

jest.mock('../../src/auth/AuthContext', () => ({
    __esModule: true,
    useAuth: () => ({
        serverUrl: null,
        setServerUrl: mockSetServerUrl,
    }),
}));

import { ServerConfigScreen } from '../../src/screens/ServerConfigScreen';

beforeEach(() => {
    jest.clearAllMocks();
    mockSetServerUrl.mockResolvedValue(undefined);
});

describe('ServerConfigScreen', () => {
    it('renders the URL input and continue button', () => {
        const { getByTestId } = render(<ServerConfigScreen />);
        expect(getByTestId('server-url-input')).toBeTruthy();
        expect(getByTestId('continue-button')).toBeTruthy();
    });

    it('rejects an obviously invalid URL with an inline error', async () => {
        const { getByTestId, queryByText } = render(<ServerConfigScreen />);

        fireEvent.changeText(getByTestId('server-url-input'), 'not-a-url');
        await act(async () => {
            fireEvent.press(getByTestId('continue-button'));
        });

        await waitFor(() => {
            expect(queryByText(/valid url/i)).toBeTruthy();
        });
        expect(mockSetServerUrl).not.toHaveBeenCalled();
    });

    it('saves and continues when the URL is valid', async () => {
        const { getByTestId } = render(<ServerConfigScreen />);

        fireEvent.changeText(
            getByTestId('server-url-input'),
            'https://wealthview.example.com',
        );
        await act(async () => {
            fireEvent.press(getByTestId('continue-button'));
        });

        await waitFor(() => {
            expect(mockSetServerUrl).toHaveBeenCalledWith('https://wealthview.example.com');
        });
    });

    it('strips trailing slashes from the saved URL', async () => {
        const { getByTestId } = render(<ServerConfigScreen />);

        fireEvent.changeText(
            getByTestId('server-url-input'),
            'http://192.168.1.50/',
        );
        await act(async () => {
            fireEvent.press(getByTestId('continue-button'));
        });

        await waitFor(() => {
            expect(mockSetServerUrl).toHaveBeenCalledWith('http://192.168.1.50');
        });
    });
});
